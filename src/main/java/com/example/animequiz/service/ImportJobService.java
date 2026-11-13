package com.example.animequiz.service;

import com.example.animequiz.entity.ImportJob;
import com.example.animequiz.repository.ImportJobRepository;
import com.example.animequiz.service.catalog.AnimeCatalogService;
import com.example.animequiz.service.enrichment.EnrichmentWorker;
import com.example.animequiz.service.importer.ShikimoriService;
import com.example.animequiz.service.importer.YummyAnimeService;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public Long startSiteImport() {
        if (currentRunning("site_import").isPresent()) {
            throw new IllegalStateException("Импорт уже выполняется");
        }
        ImportJob job = new ImportJob();
        job.setType("site_import");
        job.setState("running");
        job.setStage("enumeration");
        job.setMessage("Запуск...");
        jobRepo.save(job);

        self.getObject().runSiteImportAsync(job.getId());
        return job.getId();
    }

    @Async
    public void runSiteImportAsync(Long jobId) {
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
