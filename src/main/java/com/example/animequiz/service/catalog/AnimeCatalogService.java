package com.example.animequiz.service.catalog;

import com.example.animequiz.entity.Anime;
import com.example.animequiz.repository.AnimeRepository;
import com.example.animequiz.repository.UserAnimeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnimeCatalogService {

    private final AnimeRepository animeRepository;
    private final UserAnimeRepository userAnimeRepository;

    @Transactional(readOnly = true)
    public Map<Long, Anime> batchLookupByShikimoriIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return animeRepository.findAllByShikimoriIdIn(new ArrayList<>(ids))
                .stream()
                .filter(a -> a.getShikimoriId() != null)
                .collect(Collectors.toMap(Anime::getShikimoriId, a -> a, (a, b) -> a));
    }

    @Transactional(readOnly = true)
    public Map<Long, Anime> batchLookupByMalIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return animeRepository.findAllByMalIdIn(new ArrayList<>(ids))
                .stream()
                .filter(a -> a.getMalId() != null)
                .collect(Collectors.toMap(Anime::getMalId, a -> a, (a, b) -> a));
    }

    @Transactional(readOnly = true)
    public Map<Long, Anime> findAllByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return animeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Anime::getId, a -> a));
    }

    @Transactional
    public Anime upsert(AnimePatch patch) {
        Optional<Anime> byShiki = patch.shikimoriId() != null
                ? animeRepository.findByShikimoriId(patch.shikimoriId()) : Optional.empty();
        Optional<Anime> byMal = patch.malId() != null
                ? animeRepository.findByMalId(patch.malId()) : Optional.empty();
        Optional<Anime> byYummy = patch.yummyId() != null
                ? animeRepository.findByYummyId(patch.yummyId()) : Optional.empty();

        Set<Anime> candidates = new LinkedHashSet<>();
        byShiki.ifPresent(candidates::add);
        byMal.ifPresent(candidates::add);
        byYummy.ifPresent(candidates::add);

        Anime target;
        if (candidates.isEmpty()) {
            target = new Anime();
        } else if (candidates.size() == 1) {
            target = candidates.iterator().next();
        } else {
            List<Anime> list = new ArrayList<>(candidates);
            list.sort(Comparator.comparing(Anime::getId));
            target = list.get(0);
            for (int i = 1; i < list.size(); i++) {
                merge(target, list.get(i));
            }
        }

        applyPatch(target, patch);
        return animeRepository.save(target);
    }

    private void applyPatch(Anime target, AnimePatch p) {
        if (target.getShikimoriId() == null && p.shikimoriId() != null)
            target.setShikimoriId(p.shikimoriId());
        if (target.getMalId() == null && p.malId() != null)
            target.setMalId(p.malId());
        if (target.getYummyId() == null && p.yummyId() != null)
            target.setYummyId(p.yummyId());

        if (target.getName() == null && p.name() != null)       target.setName(p.name());
        if (target.getRussian() == null && p.russian() != null) target.setRussian(p.russian());

        if (p.title() != null) {
            target.setTitle(p.title());
        } else if (target.getTitle() == null) {
            target.setTitle(firstNonBlank(p.russian(), p.name(), "Unknown"));
        }
        if (target.getKind() == null && p.kind() != null)                   target.setKind(p.kind());
        if (target.getAiringStatus() == null && p.airingStatus() != null)   target.setAiringStatus(p.airingStatus());
        if (target.getTotalEpisodes() == null && p.totalEpisodes() != null) target.setTotalEpisodes(p.totalEpisodes());
        if (target.getUrl() == null && p.url() != null)                     target.setUrl(p.url());

        if (p.posterUrl() != null && !p.posterUrl().isBlank()) {
            target.setPosterUrl(p.posterUrl());
        }

        if (p.completeness() != null && rank(p.completeness()) > rank(target.getCompleteness())) {
            target.setCompleteness(p.completeness());
        }
    }

    @Transactional
    public void merge(Anime keep, Anime drop) {
        if (Objects.equals(keep.getId(), drop.getId())) return;
        log.info("Merge anime {} → {}", drop.getId(), keep.getId());

        Long   dShiki   = drop.getShikimoriId();
        Long   dMal     = drop.getMalId();
        Long   dYummy   = drop.getYummyId();
        String dName    = drop.getName();
        String dRussian = drop.getRussian();
        String dKind    = drop.getKind();
        String dAir     = drop.getAiringStatus();
        Integer dEps    = drop.getTotalEpisodes();
        String dUrl     = drop.getUrl();
        String dHash    = drop.getPosterHash();
        String dExt     = drop.getPosterExt();
        String dMime    = drop.getPosterMime();
        String dPUrl    = drop.getPosterUrl();
        String dPStatus = drop.getPosterStatus();

        userAnimeRepository.transferUserAnime(drop.getId(), keep.getId());

        animeRepository.delete(drop);
        animeRepository.flush();

        if (keep.getShikimoriId() == null) keep.setShikimoriId(dShiki);
        if (keep.getMalId() == null)       keep.setMalId(dMal);
        if (keep.getYummyId() == null)     keep.setYummyId(dYummy);
        if (keep.getName() == null)        keep.setName(dName);
        if (keep.getRussian() == null)     keep.setRussian(dRussian);
        if (keep.getKind() == null)        keep.setKind(dKind);
        if (keep.getAiringStatus() == null) keep.setAiringStatus(dAir);
        if (keep.getTotalEpisodes() == null) keep.setTotalEpisodes(dEps);
        if (keep.getUrl() == null)         keep.setUrl(dUrl);
        if (keep.getPosterHash() == null && dHash != null) {
            keep.setPosterHash(dHash);
            keep.setPosterExt(dExt);
            keep.setPosterMime(dMime);
            keep.setPosterUrl(dPUrl);
            keep.setPosterStatus(dPStatus);
        }

        animeRepository.save(keep);
    }

    private static String firstNonBlank(String... s) {
        for (String v : s) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static int rank(String c) {
        return switch (c == null ? "stub" : c) {
            case "full"    -> 3;
            case "partial" -> 2;
            default        -> 1;
        };
    }

    public record AnimePatch(
            Long shikimoriId, Long malId, Long yummyId,
            String name, String russian, String title,
            String kind, String airingStatus, Integer totalEpisodes,
            String url, String posterUrl, String completeness
    ) {
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
            public Long shikimoriId, malId, yummyId;
            public String name, russian, title, kind, airingStatus;
            public Integer totalEpisodes;
            public String url, posterUrl, completeness;

            public Builder shikimoriId(Long v) { this.shikimoriId = v; return this; }
            public Builder malId(Long v) { this.malId = v; return this; }
            public Builder yummyId(Long v) { this.yummyId = v; return this; }
            public Builder name(String v) { this.name = v; return this; }
            public Builder russian(String v) { this.russian = v; return this; }
            public Builder title(String v) { this.title = v; return this; }
            public Builder kind(String v) { this.kind = v; return this; }
            public Builder airingStatus(String v) { this.airingStatus = v; return this; }
            public Builder totalEpisodes(Integer v) { this.totalEpisodes = v; return this; }
            public Builder url(String v) { this.url = v; return this; }
            public Builder posterUrl(String v) { this.posterUrl = v; return this; }
            public Builder completeness(String v) { this.completeness = v; return this; }

            public AnimePatch build() {
                return new AnimePatch(shikimoriId, malId, yummyId,
                        name, russian, title, kind, airingStatus,
                        totalEpisodes, url, posterUrl, completeness);
            }
        }
    }
}