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
    public UserListView getUserListView(String username) {
        List<UserAnime> list = userAnimeRepository.findByUserId(username);
        if (list.isEmpty()) {
            return new UserListView(List.of(), 0, 0);
        }

        List<Long> animeIds = list.stream().map(UserAnime::getAnimeId).toList();
        Map<Long, Anime> byId = catalog.findAllByIds(animeIds);

        List<UserAnimeView> ready = new ArrayList<>();
        int hidden = 0;

        for (UserAnime ua : list) {
            Anime a = byId.get(ua.getAnimeId());
            if (a == null) continue;

            UserAnimeView view = new UserAnimeView(
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

            if (isFullyProcessed(view)) {
                ready.add(view);
            } else {
                hidden++;
            }
        }

        return new UserListView(ready, hidden, list.size());
    }

    private static boolean isFullyProcessed(UserAnimeView v) {
        if ("stub".equals(v.completeness())) return false;
        if (v.posterHash() != null) return true;
        if ("no_image".equals(v.posterStatus())) return true;
        return "failed".equals(v.posterStatus());
    }

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
}