package com.example.animequiz.service.importer;

import com.example.animequiz.service.catalog.AnimeCatalogService;
import java.util.List;

public interface AnimeListImporter {

    String sourceName();

    ImportResult importList(String nickname);

    default ImportResult importList(String nickname, ProgressListener onProgress) {
        return importList(nickname);
    }

    AnimeCatalogService.AnimePatch fetchFull(long sourceId);

    @FunctionalInterface
    interface ProgressListener {
        void loaded(int loaded);
    }

    record ImportResult(long userId, String nickname, int count, List<RatingEntry> ratings) {}

    record RatingEntry(
            long sourceAnimeId,
            Long shikimoriId,
            Long malId,
            Long yummyId,
            String sourceRateId,
            String status,
            Integer score,
            Integer episodesWatched,
            Integer rewatches) {}
}
