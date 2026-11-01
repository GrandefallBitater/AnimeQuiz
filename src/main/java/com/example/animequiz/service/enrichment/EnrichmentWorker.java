package com.example.animequiz.service.enrichment;

import com.example.animequiz.entity.Anime;
import com.example.animequiz.exceptions.NotFoundException;
import com.example.animequiz.repository.AnimeRepository;
import com.example.animequiz.repository.ImportJobRepository;
import com.example.animequiz.service.PosterStorageService;
import com.example.animequiz.service.catalog.AnimeCatalogService;
import com.example.animequiz.service.importer.MalService;
import com.example.animequiz.service.importer.ShikimoriService;
import com.example.animequiz.service.importer.YummyAnimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichmentWorker {

    private static final int BATCH_SIZE = 40;

    private final AnimeRepository animeRepository;
    private final AnimeCatalogService catalog;
    private final PosterStorageService posterStorage;
    private final ShikimoriService shikimori;
    private final MalService mal;
    private final YummyAnimeService yummy;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ObjectProvider<EnrichmentWorker> self;
    private final ImportJobRepository jobRepo;
    private final AtomicBoolean triggerRequested = new AtomicBoolean(false);

    public void trigger() {
        triggerRequested.set(true);
    }

    @Scheduled(fixedDelayString = "PT15S")
    public void tick() {
        if (jobRepo.findFirstByTypeAndStateOrderByIdDesc("site_import", "running").isPresent()) {
            return;
        }
        boolean triggered = triggerRequested.getAndSet(false);
        if (!triggered && !running.compareAndSet(false, true)) return;
        if (triggered && !running.compareAndSet(false, true)) {
            triggerRequested.set(true);
            return;
        }
        try { processBatch(); } finally { running.set(false); }
    }

    private void processBatch() {
        List<Anime> queue = animeRepository.findEnrichQueue(
                Instant.now(), PageRequest.of(0, BATCH_SIZE));
        if (queue.isEmpty()) return;

        log.info("EnrichmentWorker: обрабатываю {} тайтлов", queue.size());
        for (Anime a : queue) {
            try {
                self.getObject().enrichOne(a.getId());
            } catch (Exception e) {
                log.warn("Enrich fail animeId={}: {}", a.getId(), e.toString());
                markFailed(a.getId(), e.getMessage());
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enrichOne(Long animeId) {
        Anime a = animeRepository.findById(animeId).orElse(null);
        if (a == null) return;

        Set<String> sources = a.getEnrichedSources() == null
                ? new HashSet<>() : new HashSet<>(Arrays.asList(a.getEnrichedSources()));

        AnimeCatalogService.AnimePatch patch = null;

        if (a.getShikimoriId() != null && !sources.contains("shikimori")) {
            try {
                patch = shikimori.fetchFull(a.getShikimoriId());
                sources.add("shikimori");
            } catch (NotFoundException e) {
                log.info("Shikimori 404 animeId={} shikiId={} — пропускаю",
                        a.getId(), a.getShikimoriId());
                sources.add("shikimori");
            } catch (Exception e) {
                log.warn("Shikimori fail animeId={} shikiId={}: {}",
                        a.getId(), a.getShikimoriId(), e.toString());
                markFailedInPlace(a, "shikimori: " + e.getMessage());
                return;
            }
        }

        if (!sources.contains("yummy")) {
            try {
                AnimeCatalogService.AnimePatch y = null;

                if (a.getYummyId() != null) {
                    try {
                        y = yummy.fetchFull(a.getYummyId());
                    } catch (NotFoundException e) {
                        log.info("Yummy 404 animeId={} yummyId={}", a.getId(), a.getYummyId());
                    } catch (Exception e) {
                        log.warn("Yummy fetchFull fail animeId={} yummyId={}: {}",
                                a.getId(), a.getYummyId(), e.toString());
                    }
                }

                if (y == null && a.getShikimoriId() != null) {
                    List<AnimeCatalogService.AnimePatch> list =
                            yummy.fetchByShikimoriIds(List.of(a.getShikimoriId()));
                    if (!list.isEmpty()) y = list.get(0);
                }
                if (y == null && a.getMalId() != null) {
                    List<AnimeCatalogService.AnimePatch> list =
                            yummy.fetchByMalIds(List.of(a.getMalId()));
                    if (!list.isEmpty()) y = list.get(0);
                }

                if (y != null) {
                    patch = mergePatches(patch, y);
                }
                sources.add("yummy");
            } catch (Exception e) {
                log.debug("Yummy не дал данных для {}: {}", a.getId(), e.toString());
            }
        }

        if (a.getPosterUrl() == null && !sources.contains("mal") && a.getMalId() != null) {
            try {
                patch = mergePatches(patch, mal.fetchFull(a.getMalId()));
                sources.add("mal");
            } catch (Exception e) {
                log.debug("MAL не дал данных для {}: {}", a.getMalId(), e.toString());
                sources.add("mal");
            }
        }

        if (patch != null) {
            catalog.upsert(patch);
        }

        a = animeRepository.findById(animeId).orElse(null);
        if (a == null) return;

        if (a.getPosterHash() == null && a.getPosterUrl() != null && !a.getPosterUrl().isBlank()) {
            try {
                PosterStorageService.StoredPoster stored = posterStorage.downloadAndStore(a.getPosterUrl());
                a.setPosterHash(stored.hash());
                a.setPosterExt(stored.ext());
                a.setPosterMime(stored.mime());
                a.setPosterStatus("ready");
            } catch (Exception e) {
                log.warn("Постер fail animeId={} url={}: {}", a.getId(), a.getPosterUrl(), e.toString());
                a.setPosterStatus("failed");
            }
        } else if (a.getPosterUrl() == null || a.getPosterUrl().isBlank()) {
            a.setPosterStatus("no_image");
        }

        a.setEnrichedSources(sources.toArray(String[]::new));
        a.setEnrichedAt(Instant.now());
        a.setEnrichAttempts(a.getEnrichAttempts() + 1);
        a.setEnrichNextAt(null);

        if ("stub".equals(a.getCompleteness()) && patch != null) {
            a.setCompleteness("partial");
        }
        animeRepository.save(a);
    }

    private void markFailed(Long animeId, String error) {
        animeRepository.findById(animeId).ifPresent(a -> {
            int attempts = a.getEnrichAttempts() + 1;
            a.setEnrichAttempts(attempts);
            a.setEnrichLastError(error);
            a.setEnrichNextAt(Instant.now().plus(backoff(attempts)));
            animeRepository.save(a);
        });
    }

    private void markFailedInPlace(Anime a, String error) {
        int attempts = a.getEnrichAttempts() + 1;
        a.setEnrichAttempts(attempts);
        a.setEnrichLastError(error);
        a.setEnrichNextAt(Instant.now().plus(backoff(attempts)));
        animeRepository.save(a);
    }

    private static Duration backoff(int attempts) {
        long minutes = switch (attempts) {
            case 1 -> 5;
            case 2 -> 30;
            case 3 -> 120;
            case 4 -> 720;
            default -> 1440;
        };
        return Duration.ofMinutes(minutes);
    }

    private static AnimeCatalogService.AnimePatch mergePatches(
            AnimeCatalogService.AnimePatch a, AnimeCatalogService.AnimePatch b) {
        if (a == null) return b;
        if (b == null) return a;
        return AnimeCatalogService.AnimePatch.builder()
                .shikimoriId(firstNonNull(a.shikimoriId(), b.shikimoriId()))
                .malId(firstNonNull(a.malId(), b.malId()))
                .yummyId(firstNonNull(a.yummyId(), b.yummyId()))
                .name(firstNonBlank(a.name(), b.name()))
                .russian(firstNonBlank(a.russian(), b.russian()))
                .title(firstNonBlank(a.title(), b.title()))
                .kind(firstNonBlank(a.kind(), b.kind()))
                .airingStatus(firstNonBlank(a.airingStatus(), b.airingStatus()))
                .totalEpisodes(a.totalEpisodes() != null ? a.totalEpisodes() : b.totalEpisodes())
                .url(firstNonBlank(a.url(), b.url()))
                .posterUrl(firstNonBlank(a.posterUrl(), b.posterUrl()))
                .completeness(rank(a.completeness()) >= rank(b.completeness())
                        ? a.completeness() : b.completeness())
                .build();
    }

    private static <T> T firstNonNull(T a, T b) { return a != null ? a : b; }
    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
    private static int rank(String c) {
        return switch (c == null ? "stub" : c) {
            case "full" -> 3; case "partial" -> 2; default -> 1;
        };
    }
}