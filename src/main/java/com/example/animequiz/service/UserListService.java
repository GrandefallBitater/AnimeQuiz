package com.example.animequiz.service;

import com.example.animequiz.entity.Anime;
import com.example.animequiz.entity.UserAnime;
import com.example.animequiz.entity.UserImport;
import com.example.animequiz.repository.UserAnimeRepository;
import com.example.animequiz.repository.UserImportRepository;
import com.example.animequiz.service.catalog.AnimeCatalogService;
import com.example.animequiz.service.importer.AnimeListImporter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserListService {

    private final AnimeCatalogService catalog;
    private final UserAnimeRepository userAnimeRepository;
    private final UserImportRepository userImportRepository;

    @Transactional
    public void replaceUserList(String username,
                                String source,
                                AnimeListImporter.ImportResult result) {
        userAnimeRepository.deleteAllByUserId(username);

        Set<Long> shikiIds = result.ratings().stream()
                .map(AnimeListImporter.RatingEntry::shikimoriId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> malIds = result.ratings().stream()
                .map(AnimeListImporter.RatingEntry::malId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, Anime> byShiki = catalog.batchLookupByShikimoriIds(shikiIds);
        Map<Long, Anime> byMal   = catalog.batchLookupByMalIds(malIds);
        Map<Long, UserAnime> byAnimeId = new LinkedHashMap<>();

        for (AnimeListImporter.RatingEntry r : result.ratings()) {
            Anime anime = null;
            if (r.shikimoriId() != null) anime = byShiki.get(r.shikimoriId());
            if (anime == null && r.malId() != null) anime = byMal.get(r.malId());
            if (anime == null) {
                anime = catalog.upsert(
                        AnimeCatalogService.AnimePatch.builder()
                                .shikimoriId(r.shikimoriId())
                                .malId(r.malId())
                                .yummyId(r.yummyId())
                                .title("Unknown #" + r.sourceAnimeId())
                                .completeness("stub")
                                .build());
            }

            UserAnime ua = new UserAnime();
            ua.setUserId(username);
            ua.setAnimeId(anime.getId());
            ua.setSource(source);
            ua.setSourceRateId(r.sourceRateId());
            ua.setStatus(r.status());
            ua.setScore(r.score());
            ua.setEpisodesWatched(resolveEpisodes(r, anime));
            ua.setRewatches(r.rewatches());

            byAnimeId.put(anime.getId(), ua);
        }

        userAnimeRepository.saveAll(byAnimeId.values());

        UserImport ui = userImportRepository.findById(username).orElseGet(UserImport::new);
        ui.setUserId(username);
        ui.setSource(source);
        ui.setNickname(result.nickname());
        ui.setSourceUserId(result.userId());
        ui.setTotalCount(result.count());
        ui.setImportedAt(java.time.Instant.now());
        userImportRepository.save(ui);
    }

    private Integer resolveEpisodes(AnimeListImporter.RatingEntry r, Anime anime) {
        if (r.episodesWatched() != null) return r.episodesWatched();
        if ("completed".equals(r.status()) && anime.getTotalEpisodes() != null) {
            return anime.getTotalEpisodes();
        }
        return null;
    }

    @Transactional(readOnly = true)
    public UserListPage getUserListPage(String username, int page, int size, String query,
                                        Collection<String> statuses, boolean onlyLocal, String sort) {
        LoadedViews loaded = loadViews(username);

        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<UserAnimeView> filtered = loaded.ready().stream()
                .filter(v -> !onlyLocal || hasPoster(v))
                .filter(v -> statuses == null || statuses.isEmpty()
                        || statuses.contains(v.status()))
                .filter(v -> q.isEmpty() || matchesQuery(v, q))
                .collect(Collectors.toCollection(ArrayList::new));

        sortViews(filtered, sort);

        int filteredCount = filtered.size();
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        int from = Math.min(safePage * safeSize, filteredCount);
        int to = Math.min(from + safeSize, filteredCount);
        List<UserAnimeView> slice = filtered.subList(from, to);

        return new UserListPage(slice, safePage, safeSize, loaded.total(),
                loaded.ready().size(), loaded.withPoster(), filteredCount,
                loaded.hidden(), to < filteredCount, loaded.statusCounts());
    }

    private LoadedViews loadViews(String username) {
        List<UserAnime> list = new ArrayList<>(userAnimeRepository.findByUserId(username));
        list.sort(Comparator.comparing(UserAnime::getId,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        List<UserAnimeView> ready = new ArrayList<>();
        int hidden = 0;
        int withPoster = 0;

        if (!list.isEmpty()) {
            List<Long> animeIds = list.stream().map(UserAnime::getAnimeId).toList();
            Map<Long, Anime> byId = catalog.findAllByIds(animeIds);

            for (UserAnime ua : list) {
                Anime a = byId.get(ua.getAnimeId());
                if (a == null) continue;

                UserAnimeView view = toView(a, ua);
                if (!isFullyProcessed(view)) {
                    hidden++;
                    continue;
                }
                ready.add(view);
                if (hasPoster(view)) withPoster++;
                String status = view.status() == null ? "" : view.status();
                statusCounts.merge(status, 1, Integer::sum);
            }
        }

        return new LoadedViews(ready, hidden, withPoster, statusCounts, list.size());
    }

    private static UserAnimeView toView(Anime a, UserAnime ua) {
        return new UserAnimeView(
                a.getId(),
                a.getTitle(),
                a.getRussian(),
                a.getName(),
                a.getKind(),
                a.getAiringStatus(),
                a.getTotalEpisodes(),
                a.getUrl(),
                a.getPosterHash(),
                a.getPosterExt(),
                a.getPosterStatus(),
                a.getCompleteness(),
                ua.getStatus(),
                ua.getScore(),
                ua.getEpisodesWatched(),
                ua.getRewatches(),
                ua.getSource()
        );
    }

    private static boolean hasPoster(UserAnimeView v) {
        return v.posterHash() != null && v.posterExt() != null;
    }

    private static boolean matchesQuery(UserAnimeView v, String q) {
        return contains(v.title(), q) || contains(v.name(), q) || contains(v.russian(), q);
    }

    private static boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }

    private static String titleOf(UserAnimeView v) {
        String ru = v.russian() == null ? "" : v.russian().trim();
        if (!ru.isEmpty()) return ru;
        String title = v.title() == null ? "" : v.title().trim();
        if (!title.isEmpty()) return title;
        String name = v.name() == null ? "" : v.name().trim();
        return name.isEmpty() ? "Без названия" : name;
    }

    private static boolean startsWithLetter(String s) {
        if (s == null || s.isEmpty()) return false;
        return Character.isLetter(s.codePointAt(0));
    }

    private static final List<String> STATUS_ORDER = List.of(
            "watching", "rewatching", "planned", "completed", "on_hold", "dropped");

    private static void sortViews(List<UserAnimeView> list, String sort) {
        String mode = sort == null ? "default" : sort;
        Collator collator = Collator.getInstance(Locale.forLanguageTag("ru"));
        switch (mode) {
            case "title-asc", "title-desc" -> {
                int dir = "title-asc".equals(mode) ? 1 : -1;
                list.sort((a, b) -> {
                    String ka = titleOf(a);
                    String kb = titleOf(b);
                    boolean la = startsWithLetter(ka);
                    boolean lb = startsWithLetter(kb);
                    if (la != lb) return la ? -1 : 1;
                    return dir * collator.compare(ka, kb);
                });
            }
            case "score-desc" -> list.sort(Comparator.comparingInt(
                    (UserAnimeView v) -> v.score() == null ? 0 : v.score()).reversed());
            case "score-asc" -> list.sort(Comparator.comparingInt(
                    (UserAnimeView v) -> v.score() == null ? 0 : v.score()));
            case "status" -> list.sort((a, b) -> {
                int ia = STATUS_ORDER.indexOf(a.status());
                int ib = STATUS_ORDER.indexOf(b.status());
                int sa = ia == -1 ? 99 : ia;
                int sb = ib == -1 ? 99 : ib;
                return sa != sb ? sa - sb : collator.compare(titleOf(a), titleOf(b));
            });
            default -> { }
        }
    }

    @Transactional(readOnly = true)
    public UserListView getUserListView(String username) {
        LoadedViews loaded = loadViews(username);
        return new UserListView(loaded.ready(), loaded.hidden(), loaded.total());
    }

    private static boolean isFullyProcessed(UserAnimeView v) {
        if ("stub".equals(v.completeness())) return false;
        if (v.posterHash() != null) return true;
        if ("no_image".equals(v.posterStatus())) return true;
        return "failed".equals(v.posterStatus());
    }

    private record LoadedViews(
            List<UserAnimeView> ready,
            int hidden,
            int withPoster,
            Map<String, Integer> statusCounts,
            int total
    ) {}

    @Transactional(readOnly = true)
    public Optional<UserImport> getLastImport(String username) {
        return userImportRepository.findById(username);
    }

    public record UserAnimeView(
            Long animeId, String title, String russian, String name,
            String kind, String airingStatus, Integer totalEpisodes, String url,
            String posterHash, String posterExt, String posterStatus,
            String completeness,
            String status, Integer score, Integer episodesWatched,
            Integer rewatches, String source
    ) {}

    public record UserListView(
            List<UserAnimeView> titles,
            int hiddenCount,
            int totalCount
    ) {}

    public record UserListPage(
            List<UserAnimeView> titles,
            int page,
            int size,
            int totalCount,
            int readyCount,
            int withPosterCount,
            int filteredCount,
            int hiddenCount,
            boolean hasMore,
            Map<String, Integer> statusCounts
    ) {}
}