package com.example.animequiz.service.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.animequiz.entity.Anime;
import com.example.animequiz.repository.AnimeRepository;
import com.example.animequiz.repository.UserAnimeRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnimeCatalogServiceTest {

    @Mock
    private AnimeRepository animeRepository;

    @Mock
    private UserAnimeRepository userAnimeRepository;

    @InjectMocks
    private AnimeCatalogService catalog;

    @Test
    void batchLookupsReturnEmptyMapForEmptyInput() {
        assertThat(catalog.batchLookupByShikimoriIds(List.of())).isEmpty();
        assertThat(catalog.batchLookupByMalIds(null)).isEmpty();
        assertThat(catalog.findAllByIds(List.of())).isEmpty();

        verifyNoInteractions(animeRepository);
    }

    @Test
    void batchLookupByShikimoriIdIndexesTitlesAndSkipsMissingIds() {
        Anime first = anime(1, 10L, null);
        Anime second = anime(2, 20L, null);
        Anime withoutShiki = anime(3, null, null);

        when(animeRepository.findAllByShikimoriIdIn(any())).thenReturn(List.of(first, second, withoutShiki));

        assertThat(catalog.batchLookupByShikimoriIds(List.of(10L, 20L)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(10L, first, 20L, second));
    }

    @Test
    void batchLookupByMalIdIndexesTitles() {
        Anime first = anime(1, null, 10L);
        Anime second = anime(2, null, 20L);

        when(animeRepository.findAllByMalIdIn(any())).thenReturn(List.of(first, second));

        assertThat(catalog.batchLookupByMalIds(List.of(10L, 20L)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(10L, first, 20L, second));
    }

    @Test
    void findAllByIdsIndexesTitles() {
        Anime first = anime(1, null, null);
        Anime second = anime(2, null, null);

        when(animeRepository.findAllById(any())).thenReturn(List.of(first, second));

        assertThat(catalog.findAllByIds(List.of(1L, 2L)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1L, first, 2L, second));
    }

    @Test
    void upsertCreatesTitleAndAppliesPatch() {
        when(animeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Anime saved = catalog.upsert(AnimeCatalogService.AnimePatch.builder()
                .shikimoriId(10L)
                .name("Name")
                .kind("tv")
                .totalEpisodes(12)
                .completeness("partial")
                .build());

        assertThat(saved.getId()).isNull();
        assertThat(saved.getShikimoriId()).isEqualTo(10L);
        assertThat(saved.getName()).isEqualTo("Name");
        assertThat(saved.getTitle()).isEqualTo("Name");
        assertThat(saved.getKind()).isEqualTo("tv");
        assertThat(saved.getTotalEpisodes()).isEqualTo(12);
        assertThat(saved.getCompleteness()).isEqualTo("partial");
    }

    @Test
    void upsertFallsBackToUnknownTitleWhenEverythingIsMissing() {
        when(animeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Anime saved = catalog.upsert(AnimeCatalogService.AnimePatch.builder().build());

        assertThat(saved.getTitle()).isEqualTo("Unknown");
    }

    @Test
    void upsertDoesNotOverwriteKnownFieldsButUpdatesTitleAndCompleteness() {
        Anime existing = anime(1, 10L, null);
        existing.setName("Old name");
        existing.setTitle("Old title");
        existing.setTotalEpisodes(12);
        existing.setCompleteness("partial");
        when(animeRepository.findByShikimoriId(10L)).thenReturn(Optional.of(existing));
        when(animeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Anime saved = catalog.upsert(AnimeCatalogService.AnimePatch.builder()
                .shikimoriId(10L)
                .name("New name")
                .title("New title")
                .totalEpisodes(24)
                .completeness("full")
                .build());

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getName()).isEqualTo("Old name");
        assertThat(saved.getTitle()).isEqualTo("New title");
        assertThat(saved.getTotalEpisodes()).isEqualTo(12);
        assertThat(saved.getCompleteness()).isEqualTo("full");
    }

    @Test
    void upsertDoesNotDowngradeCompleteness() {
        Anime existing = anime(1, 10L, null);
        existing.setCompleteness("full");
        when(animeRepository.findByShikimoriId(10L)).thenReturn(Optional.of(existing));
        when(animeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Anime saved = catalog.upsert(AnimeCatalogService.AnimePatch.builder()
                .shikimoriId(10L)
                .completeness("stub")
                .build());

        assertThat(saved.getCompleteness()).isEqualTo("full");
    }

    @Test
    void upsertMergesDifferentRecordsThatPointToTheSameTitle() {
        Anime keep = anime(1, 10L, null);
        keep.setName("Keep");
        keep.setCompleteness("partial");
        Anime drop = anime(2, null, 20L);
        drop.setName("Drop");
        drop.setUrl("https://example.test/anime");
        drop.setKind("tv");
        drop.setPosterHash("hash");
        drop.setPosterExt("png");
        drop.setPosterMime("image/png");
        drop.setPosterUrl("https://example.test/poster.png");
        drop.setPosterStatus("ready");

        when(animeRepository.findByShikimoriId(10L)).thenReturn(Optional.of(keep));
        when(animeRepository.findByMalId(20L)).thenReturn(Optional.of(drop));
        when(animeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Anime saved = catalog.upsert(AnimeCatalogService.AnimePatch.builder()
                .shikimoriId(10L)
                .malId(20L)
                .build());

        verify(userAnimeRepository).transferUserAnime(2L, 1L);
        verify(animeRepository).delete(drop);
        verify(animeRepository).flush();

        assertThat(saved).isSameAs(keep);
        assertThat(saved.getMalId()).isEqualTo(20L);
        assertThat(saved.getName()).isEqualTo("Keep");
        assertThat(saved.getUrl()).isEqualTo("https://example.test/anime");
        assertThat(saved.getKind()).isEqualTo("tv");
        assertThat(saved.getPosterHash()).isEqualTo("hash");
        assertThat(saved.getPosterExt()).isEqualTo("png");
        assertThat(saved.getPosterStatus()).isEqualTo("ready");
    }

    @Test
    void mergeDoesNothingForTheSameRecord() {
        Anime anime = anime(1, 10L, null);

        catalog.merge(anime, anime);

        verify(userAnimeRepository, never()).transferUserAnime(any(), any());
        verify(animeRepository, never()).delete(any());
    }

    @Test
    void mergeKeepsExistingPoster() {
        Anime keep = anime(1, 10L, null);
        keep.setPosterHash("keep-hash");
        keep.setPosterExt("png");
        Anime drop = anime(2, null, 20L);
        drop.setPosterHash("drop-hash");
        drop.setPosterExt("jpg");
        when(animeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        catalog.merge(keep, drop);

        assertThat(keep.getPosterHash()).isEqualTo("keep-hash");
        assertThat(keep.getPosterExt()).isEqualTo("png");
    }

    private static Anime anime(long id, Long shikimoriId, Long malId) {
        Anime anime = new Anime();
        anime.setId(id);
        anime.setShikimoriId(shikimoriId);
        anime.setMalId(malId);
        return anime;
    }
}
