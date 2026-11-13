package com.example.animequiz.service;

import com.example.animequiz.entity.ImportJob;
import com.example.animequiz.repository.ImportJobRepository;
import com.example.animequiz.service.catalog.AnimeCatalogService;
import com.example.animequiz.service.enrichment.EnrichmentWorker;
import com.example.animequiz.service.importer.AnimeListImporter;
import com.example.animequiz.service.importer.ShikimoriService;
import com.example.animequiz.service.importer.YummyAnimeService;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportJobService {

    private final ImportJobRepository jobRepo;
    private final ShikimoriService shikimori;
    private final YummyAnimeService yummy;
    private final AnimeCatalogService catalog;
    private final EnrichmentWorker enrichmentWorker;
    private final ObjectProvider<ImportJobService> self;
    private final ActiveUserImports activeImports;
    // Held while an import is running, so two concurrent requests cannot start the same job.
    private final AtomicBoolean importRunning = new AtomicBoolean(false);
    private final UserListService userListService;

    @PostConstruct
    public void init() {
        int countOfFailedJobs = jobRepo.failAllRunningJobs(Instant.now(), "Прервано перезапуском приложения");
        if (countOfFailedJobs > 0) {
            log.warn("{} импортов помечено как проваленные", countOfFailedJobs);
        }
    }

    @Transactional(readOnly = true)
    public Optional<ImportJob> currentRunning(String type) {
        return jobRepo.findFirstByTypeAndStateOrderByIdDesc(type, "running");
    }

    @Transactional(readOnly = true)
    public Optional<ImportJob> lastJob(String type) {
        List<ImportJob> list = jobRepo.findTop10ByTypeOrderByIdDesc(type);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Long startUserImport(String username, AnimeListImporter importer, String nickname) {
        ImportJob job = new ImportJob();
        job.setType("user_import");
        job.setState("running");
        job.setStage("enumeration");
        job.setMessage("Запуск...");
        jobRepo.save(job);

        if (!activeImports.tryStart(username, job.getId())) {
            jobRepo.delete(job);
            throw new IllegalStateException("Импорт уже выполняется");
        }

        try {
            self.getObject().runUserImportAsync(job.getId(), importer, username, nickname);
        } catch (RuntimeException e) {
            activeImports.finish(username);
            fail(job, e.getClass().getSimpleName());
            throw e;
        }
        return job.getId();
    }

    @Async
    public void runUserImportAsync(Long jobId, AnimeListImporter importer, String username, String nickname) {
        try {
            AnimeListImporter.ImportResult result = runUserImport(jobId, importer, username, nickname);
            changeJob(
                    jobId,
                    job -> finish(
                            job,
                            "Импортировано: " + result.count() + " (" + importer.sourceName() + ": " + result.nickname()
                                    + ")"));
        } catch (Exception e) {
            log.error("Импорт списка пользователя {} провалился", username, e);
            changeJob(jobId, job -> fail(job, e.getClass().getSimpleName()));
        } finally {
            activeImports.finish(username);
        }
    }

    private AnimeListImporter.ImportResult runUserImport(
            Long jobId, AnimeListImporter importer, String username, String nickname) {
        AnimeListImporter.ImportResult result =
                importer.importList(nickname, loaded -> onLoaded(jobId, username, loaded));

        progress(jobId, "saving", 0, result.count(), "Сохранение " + result.count() + " записей...");
        userListService.replaceUserList(username, importer.sourceName(), result);
        enrichmentWorker.trigger();
        return result;
    }

    private void onLoaded(Long jobId, String username, int loaded) {
        activeImports.touch(username);
        String message = loaded > 0 ? "Загружено " + loaded + " записей" : "Загрузка списка...";
        progress(jobId, "enumeration", loaded, null, message);
    }

    private void progress(Long jobId, String stage, int processed, Integer total, String message) {
        changeJob(jobId, job -> {
            job.setStage(stage);
            job.setProcessedCount(processed);
            job.setTotalEstimate(total);
            job.setMessage(message);
            jobRepo.save(job);
        });
    }

    private void changeJob(Long jobId, Consumer<ImportJob> change) {
        jobRepo.findById(jobId).ifPresentOrElse(change, () -> log.warn("Задача импорта {} не найдена", jobId));
    }

    @Transactional(readOnly = true)
    public Optional<ImportJob> jobById(Long jobId) {
        return jobRepo.findById(jobId);
    }

    public Long startSiteImport() {
        if (currentRunning("site_import").isPresent()) {
            throw new IllegalStateException("Импорт уже выполняется");
        }
        if (!importRunning.compareAndSet(false, true)) {
            throw new IllegalStateException("Импорт уже выполняется");
        }
        try {
            ImportJob job = new ImportJob();
            job.setType("site_import");
            job.setState("running");
            job.setStage("enumeration");
            job.setMessage("Запуск...");
            jobRepo.save(job);

            self.getObject().runSiteImportAsync(job.getId());
            return job.getId();
        } catch (RuntimeException e) {
            importRunning.set(false);
            throw e;
        }
    }

    @Async
    public void runSiteImportAsync(Long jobId) {
        try {
            runSiteImport(jobId);
        } finally {
            importRunning.set(false);
        }
    }

    private void runSiteImport(Long jobId) {
        ImportJob job = jobRepo.findById(jobId).orElseThrow();
        try {
            update(job, "enumeration", 0, null, "Перечисление каталога Shikimori...");
            List<AnimeCatalogService.AnimePatch> catalogPatches = shikimori.fetchSiteCatalog();
            update(job, "enumeration", 0, catalogPatches.size(), "Сохранение " + catalogPatches.size() + " тайтлов...");

            int saved = 0;
            for (AnimeCatalogService.AnimePatch p : catalogPatches) {
                catalog.upsert(p);
                saved++;
                if (saved % 100 == 0) {
                    update(
                            job,
                            "enumeration",
                            saved,
                            catalogPatches.size(),
                            "Сохранено " + saved + " / " + catalogPatches.size());
                }
            }
            update(job, "enumeration", saved, catalogPatches.size(), "Каталог Shikimori загружен");

            update(job, "yummy", 0, catalogPatches.size(), "Обогащение через YummyAnime...");
            List<Long> shikiIds = catalogPatches.stream()
                    .map(AnimeCatalogService.AnimePatch::shikimoriId)
                    .filter(java.util.Objects::nonNull)
                    .toList();

            int yummyProcessed = 0;
            int batchSize = 100;
            for (int i = 0; i < shikiIds.size(); i += batchSize) {
                List<Long> batch = shikiIds.subList(i, Math.min(i + batchSize, shikiIds.size()));
                try {
                    List<AnimeCatalogService.AnimePatch> patches = yummy.fetchByShikimoriIds(batch);
                    for (AnimeCatalogService.AnimePatch p : patches) catalog.upsert(p);
                } catch (Exception e) {
                    log.warn("Yummy batch {} fail: {}", i / batchSize, e.toString());
                }
                yummyProcessed += batch.size();
                update(
                        job,
                        "yummy",
                        yummyProcessed,
                        shikiIds.size(),
                        "YummyAnime: " + yummyProcessed + " / " + shikiIds.size());
                saveCheckpoint(job, "yummyOffset", yummyProcessed);
            }

            update(job, "posters", 0, null, "Запуск фонового скачивания постеров...");
            enrichmentWorker.trigger();

            finish(job, "Импорт завершён. Постеры догружаются в фоне.");
        } catch (Exception e) {
            log.error("Site import failed", e);
            fail(job, e.getClass().getSimpleName());
        }
    }

    private void update(ImportJob job, String stage, int processed, Integer total, String message) {
        ImportJob fresh = jobRepo.findById(job.getId()).orElseThrow();
        fresh.setStage(stage);
        fresh.setProcessedCount(processed);
        if (total != null) fresh.setTotalEstimate(total);
        fresh.setMessage(message);
        jobRepo.save(fresh);
    }

    private void saveCheckpoint(ImportJob job, String key, Object value) {
        ImportJob fresh = jobRepo.findById(job.getId()).orElseThrow();
        Map<String, Object> cp = fresh.getCheckpoint() == null ? new HashMap<>() : new HashMap<>(fresh.getCheckpoint());
        cp.put(key, value);
        fresh.setCheckpoint(cp);
        jobRepo.save(fresh);
    }

    private void finish(ImportJob job, String message) {
        ImportJob fresh = jobRepo.findById(job.getId()).orElseThrow();
        fresh.setState("done");
        fresh.setFinishedAt(Instant.now());
        fresh.setMessage(message);
        jobRepo.save(fresh);
    }

    private void fail(ImportJob job, String error) {
        ImportJob fresh = jobRepo.findById(job.getId()).orElseThrow();
        fresh.setState("failed");
        fresh.setFinishedAt(Instant.now());
        fresh.setError(error);
        jobRepo.save(fresh);
    }
}
