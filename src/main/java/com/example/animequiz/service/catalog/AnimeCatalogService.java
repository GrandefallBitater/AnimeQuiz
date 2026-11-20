package com.example.animequiz.service.catalog;

import com.example.animequiz.entity.Anime;
import com.example.animequiz.repository.AnimeRepository;
import com.example.animequiz.repository.UserAnimeRepository;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnimeCatalogService {

    private final AnimeRepository animeRepository;
    private final UserAnimeRepository userAnimeRepository;

    @Transactional(readOnly = true)
    public Map<Long, Anime> batchLookupByShikimoriIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return animeRepository.findAllByShikimoriIdIn(new HashSet<>(ids)).stream()
                .filter(a -> a.getShikimoriId() != null)
                .collect(Collectors.toMap(Anime::getShikimoriId, a -> a, (a, b) -> a));
    }

    @Transactional(readOnly = true)
    public Map<Long, Anime> batchLookupByMalIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return animeRepository.findAllByMalIdIn(new HashSet<>(ids)).stream()
                .filter(a -> a.getMalId() != null)
                .collect(Collectors.toMap(Anime::getMalId, a -> a, (a, b) -> a));
    }

    @Transactional(readOnly = true)
    public Map<Long, Anime> findAllByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return animeRepository.findAllById(ids).stream().collect(Collectors.toMap(Anime::getId, a -> a));
    }

    @Transactional
    public Anime upsert(AnimePatch patch) {
        Optional<Anime> byShiki =
                patch.shikimoriId() != null ? animeRepository.findByShikimoriId(patch.shikimoriId()) : Optional.empty();
        Optional<Anime> byMal = patch.malId() != null ? animeRepository.findByMalId(patch.malId()) : Optional.empty();
        Optional<Anime> byYummy =
                patch.yummyId() != null ? animeRepository.findByYummyId(patch.yummyId()) : Optional.empty();

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

    @Transactional
    public List<Anime> upsertBatch(List<AnimePatch> patches) {
        if (patches == null || patches.isEmpty()) return List.of();

        AnimeLookup lookup = buildLookup(loadByShikimoriIds(patches), loadByMalIds(patches), loadByYummyIds(patches));

        LinkedHashSet<Anime> candidates = new LinkedHashSet<>();
        for (AnimePatch patch : patches) {
            Anime target = resolveTarget(findMatches(patch, lookup), candidates, lookup);
            applyPatch(target, patch);
            indexAnime(target, lookup);
            candidates.add(target);
        }
        return animeRepository.saveAll(candidates);
    }

    private Anime resolveTarget(Set<Anime> matches, Set<Anime> candidates, AnimeLookup lookup) {
        if (matches.isEmpty()) return new Anime();
        if (matches.size() == 1) return matches.iterator().next();

        List<Anime> ordered = new ArrayList<>(matches);
        ordered.sort(Comparator.comparing((Anime a) -> a.getId() == null ? Long.MAX_VALUE : a.getId()));

        Anime target = ordered.get(0);
        for (int i = 1; i < ordered.size(); i++) {
            absorb(target, ordered.get(i), candidates, lookup);
        }
        return target;
    }

    private void absorb(Anime keep, Anime drop, Set<Anime> candidates, AnimeLookup lookup) {
        if (drop.getId() != null) {
            merge(keep, drop);
        } else {
            copyMissingFields(keep, drop);
        }
        candidates.remove(drop);
        repointLookup(lookup, drop, keep);
    }

    private void repointLookup(AnimeLookup lookup, Anime from, Anime to) {
        replaceReferences(lookup.byShikimoriId(), from, to);
        replaceReferences(lookup.byMalId(), from, to);
        replaceReferences(lookup.byYummyId(), from, to);
    }

    private void replaceReferences(Map<Long, Anime> index, Anime from, Anime to) {
        for (Map.Entry<Long, Anime> entry : index.entrySet()) {
            if (entry.getValue() == from) entry.setValue(to);
        }
    }

    private Set<Anime> loadByShikimoriIds(List<AnimePatch> patches) {
        Set<Long> ids = patches.stream()
                .map(AnimePatch::shikimoriId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Set.of() : animeRepository.findAllByShikimoriIdIn(ids);
    }

    private Set<Anime> loadByMalIds(List<AnimePatch> patches) {
        Set<Long> ids =
                patches.stream().map(AnimePatch::malId).filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Set.of() : animeRepository.findAllByMalIdIn(ids);
    }

    private Set<Anime> loadByYummyIds(List<AnimePatch> patches) {
        Set<Long> ids = patches.stream()
                .map(AnimePatch::yummyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Set.of() : animeRepository.findAllByYummyIdIn(ids);
    }

    private Set<Anime> findMatches(AnimePatch candidate, AnimeLookup lookup) {
        Set<Anime> matches = new LinkedHashSet<>();

        addIfPresent(matches, lookup.byShikimoriId(), candidate.shikimoriId());
        addIfPresent(matches, lookup.byMalId(), candidate.malId());
        addIfPresent(matches, lookup.byYummyId(), candidate.yummyId());

        return matches;
    }

    private void addIfPresent(Set<Anime> matches, Map<Long, Anime> map, Long id) {
        if (id != null) {
            Anime found = map.get(id);
            if (found != null) {
                matches.add(found);
            }
        }
    }

    private record AnimeLookup(Map<Long, Anime> byShikimoriId, Map<Long, Anime> byMalId, Map<Long, Anime> byYummyId) {}

    private AnimeLookup buildLookup(Set<Anime> byShiki, Set<Anime> byMal, Set<Anime> byYummy) {
        AnimeLookup lookup = new AnimeLookup(new HashMap<>(), new HashMap<>(), new HashMap<>());

        indexAnimes(byShiki, lookup);
        indexAnimes(byMal, lookup);
        indexAnimes(byYummy, lookup);

        return lookup;
    }

    private void indexAnimes(Set<Anime> animes, AnimeLookup lookup) {
        for (Anime a : animes) indexAnime(a, lookup);
    }

    private void indexAnime(Anime a, AnimeLookup lookup) {
        if (a.getShikimoriId() != null) lookup.byShikimoriId().put(a.getShikimoriId(), a);
        if (a.getMalId() != null) lookup.byMalId().put(a.getMalId(), a);
        if (a.getYummyId() != null) lookup.byYummyId().put(a.getYummyId(), a);
    }

    private void applyPatch(Anime target, AnimePatch p) {
        if (target.getShikimoriId() == null && p.shikimoriId() != null) target.setShikimoriId(p.shikimoriId());
        if (target.getMalId() == null && p.malId() != null) target.setMalId(p.malId());
        if (target.getYummyId() == null && p.yummyId() != null) target.setYummyId(p.yummyId());

        if (target.getName() == null && p.name() != null) target.setName(p.name());
        if (target.getRussian() == null && p.russian() != null) target.setRussian(p.russian());

        if (p.title() != null) {
            target.setTitle(p.title());
        } else if (target.getTitle() == null) {
            target.setTitle(firstNonBlank(p.russian(), p.name(), "Unknown"));
        }
        if (target.getKind() == null && p.kind() != null) target.setKind(p.kind());
        if (target.getAiringStatus() == null && p.airingStatus() != null) target.setAiringStatus(p.airingStatus());
        if (target.getTotalEpisodes() == null && p.totalEpisodes() != null) target.setTotalEpisodes(p.totalEpisodes());
        if (target.getUrl() == null && p.url() != null) target.setUrl(p.url());

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

        userAnimeRepository.transferUserAnime(drop.getId(), keep.getId());

        animeRepository.delete(drop);
        animeRepository.flush();

        copyMissingFields(keep, drop);

        animeRepository.save(keep);
    }

    private void copyMissingFields(Anime keep, Anime drop) {
        if (keep.getShikimoriId() == null) keep.setShikimoriId(drop.getShikimoriId());
        if (keep.getMalId() == null) keep.setMalId(drop.getMalId());
        if (keep.getYummyId() == null) keep.setYummyId(drop.getYummyId());
        if (keep.getName() == null) keep.setName(drop.getName());
        if (keep.getRussian() == null) keep.setRussian(drop.getRussian());
        if (keep.getKind() == null) keep.setKind(drop.getKind());
        if (keep.getAiringStatus() == null) keep.setAiringStatus(drop.getAiringStatus());
        if (keep.getTotalEpisodes() == null) keep.setTotalEpisodes(drop.getTotalEpisodes());
        if (keep.getUrl() == null) keep.setUrl(drop.getUrl());
        if (keep.getPosterHash() == null && drop.getPosterHash() != null) {
            keep.setPosterHash(drop.getPosterHash());
            keep.setPosterExt(drop.getPosterExt());
            keep.setPosterMime(drop.getPosterMime());
            keep.setPosterUrl(drop.getPosterUrl());
            keep.setPosterStatus(drop.getPosterStatus());
        }
    }

    private static String firstNonBlank(String... s) {
        for (String v : s) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static int rank(String c) {
        return switch (c == null ? "stub" : c) {
            case "full" -> 3;
            case "partial" -> 2;
            default -> 1;
        };
    }

    public record AnimePatch(
            Long shikimoriId,
            Long malId,
            Long yummyId,
            String name,
            String russian,
            String title,
            String kind,
            String airingStatus,
            Integer totalEpisodes,
            String url,
            String posterUrl,
            String completeness) {
        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            public Long shikimoriId, malId, yummyId;
            public String name, russian, title, kind, airingStatus;
            public Integer totalEpisodes;
            public String url, posterUrl, completeness;

            public Builder shikimoriId(Long v) {
                this.shikimoriId = v;
                return this;
            }

            public Builder malId(Long v) {
                this.malId = v;
                return this;
            }

            public Builder yummyId(Long v) {
                this.yummyId = v;
                return this;
            }

            public Builder name(String v) {
                this.name = v;
                return this;
            }

            public Builder russian(String v) {
                this.russian = v;
                return this;
            }

            public Builder title(String v) {
                this.title = v;
                return this;
            }

            public Builder kind(String v) {
                this.kind = v;
                return this;
            }

            public Builder airingStatus(String v) {
                this.airingStatus = v;
                return this;
            }

            public Builder totalEpisodes(Integer v) {
                this.totalEpisodes = v;
                return this;
            }

            public Builder url(String v) {
                this.url = v;
                return this;
            }

            public Builder posterUrl(String v) {
                this.posterUrl = v;
                return this;
            }

            public Builder completeness(String v) {
                this.completeness = v;
                return this;
            }

            public AnimePatch build() {
                return new AnimePatch(
                        shikimoriId,
                        malId,
                        yummyId,
                        name,
                        russian,
                        title,
                        kind,
                        airingStatus,
                        totalEpisodes,
                        url,
                        posterUrl,
                        completeness);
            }
        }
    }
}
