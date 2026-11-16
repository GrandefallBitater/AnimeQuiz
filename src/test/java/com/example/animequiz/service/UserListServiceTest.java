package com.example.animequiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.animequiz.entity.Anime;
import com.example.animequiz.entity.UserAnime;
import com.example.animequiz.entity.UserImport;
import com.example.animequiz.repository.UserAnimeRepository;
import com.example.animequiz.repository.UserImportRepository;
import com.example.animequiz.service.catalog.AnimeCatalogService;
import com.example.animequiz.service.importer.AnimeListImporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserListServiceTest {

    @Mock
    private AnimeCatalogService catalog;

    @Mock
    private UserAnimeRepository userAnimeRepository;

    @Mock
    private UserImportRepository userImportRepository;

    @InjectMocks
    private UserListService userListService;

    @Test
    void replaceUserListMatchesAnimeAndCreatesStubsForUnknownTitles() {
        Anime byShiki = anime(10, "Shiki title", null);
        byShiki.setTotalEpisodes(24);
        Anime byMal = anime(20, "MAL title", null);
        Anime stub = anime(30, "Unknown #3", null);

        when(catalog.batchLookupByShikimoriIds(Set.of(1L))).thenReturn(Map.of(1L, byShiki));
        when(catalog.batchLookupByMalIds(Set.of(2L))).thenReturn(Map.of(2L, byMal));
        when(catalog.upsert(any())).thenReturn(stub);

        List<AnimeListImporter.RatingEntry> ratings = List.of(
                new AnimeListImporter.RatingEntry(101, 1L, null, null, "r1", "completed", 9, null, 0),
                new AnimeListImporter.RatingEntry(102, null, 2L, null, "r2", "completed", 8, 3, 1),
                new AnimeListImporter.RatingEntry(103, null, null, null, "r3", "planned", 0, null, 0));

        userListService.replaceUserList(
                "alice", "shikimori", new AnimeListImporter.ImportResult(7L, "nick", 3, ratings));

        verify(userAnimeRepository).deleteAllByUserId("alice");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<UserAnime>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(userAnimeRepository).saveAll(saved.capture());
        List<UserAnime> savedList = new ArrayList<>();
        saved.getValue().forEach(savedList::add);
        assertThat(savedList).hasSize(3);

        UserAnime first = savedList.get(0);
        assertThat(first.getUserId()).isEqualTo("alice");
        assertThat(first.getAnimeId()).isEqualTo(10);
        assertThat(first.getSource()).isEqualTo("shikimori");
        assertThat(first.getSourceRateId()).isEqualTo("r1");
        assertThat(first.getStatus()).isEqualTo("completed");
        assertThat(first.getScore()).isEqualTo(9);
        assertThat(first.getEpisodesWatched()).isEqualTo(24);
        assertThat(first.getRewatches()).isZero();

        UserAnime second = savedList.get(1);
        assertThat(second.getAnimeId()).isEqualTo(20);
        assertThat(second.getEpisodesWatched()).isEqualTo(3);
        assertThat(second.getRewatches()).isEqualTo(1);

        UserAnime third = savedList.get(2);
        assertThat(third.getAnimeId()).isEqualTo(30);
        assertThat(third.getEpisodesWatched()).isNull();
        assertThat(third.getStatus()).isEqualTo("planned");

        ArgumentCaptor<UserImport> imported = ArgumentCaptor.forClass(UserImport.class);
        verify(userImportRepository).save(imported.capture());
        assertThat(imported.getValue().getUserId()).isEqualTo("alice");
        assertThat(imported.getValue().getSource()).isEqualTo("shikimori");
        assertThat(imported.getValue().getNickname()).isEqualTo("nick");
        assertThat(imported.getValue().getSourceUserId()).isEqualTo(7L);
        assertThat(imported.getValue().getTotalCount()).isEqualTo(3);
        assertThat(imported.getValue().getImportedAt()).isNotNull();
    }

    @Test
    void replaceUserListKeepsOnlyOneEntryPerAnime() {
        Anime anime = anime(10, "Title", null);
        when(catalog.batchLookupByShikimoriIds(Set.of(1L))).thenReturn(Map.of(1L, anime));

        List<AnimeListImporter.RatingEntry> ratings = List.of(
                new AnimeListImporter.RatingEntry(101, 1L, null, null, "r1", "watching", 7, 1, 0),
                new AnimeListImporter.RatingEntry(102, 1L, null, null, "r2", "completed", 9, 12, 1));

        userListService.replaceUserList(
                "alice", "shikimori", new AnimeListImporter.ImportResult(7L, "nick", 2, ratings));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<UserAnime>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(userAnimeRepository).saveAll(saved.capture());
        List<UserAnime> savedList = new ArrayList<>();
        saved.getValue().forEach(savedList::add);
        assertThat(savedList).hasSize(1);
        assertThat(savedList.get(0).getStatus()).isEqualTo("completed");
    }

    @Test
    void getUserListPageFiltersHidesIncompleteTitlesAndBuildsCounters() {
        stubViews();

        UserListService.UserListPage page =
                userListService.getUserListPage("alice", 0, 10, "nar", Set.of(), false, "default");

        assertThat(page.totalCount()).isEqualTo(4);
        assertThat(page.readyCount()).isEqualTo(2);
        assertThat(page.withPosterCount()).isEqualTo(1);
        assertThat(page.filteredCount()).isEqualTo(1);
        assertThat(page.hiddenCount()).isEqualTo(2);
        assertThat(page.titles())
                .extracting(UserListService.UserAnimeView::title)
                .containsExactly("Naruto");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.statusCounts()).containsEntry("watching", 1).containsEntry("completed", 1);
    }

    @Test
    void getUserListPagePaginatesAndSortsByScore() {
        stubViews();

        UserListService.UserListPage first =
                userListService.getUserListPage("alice", 0, 1, null, null, false, "score-desc");
        UserListService.UserListPage second =
                userListService.getUserListPage("alice", 1, 1, null, null, false, "score-desc");

        assertThat(first.titles())
                .extracting(UserListService.UserAnimeView::title)
                .containsExactly("Bleach");
        assertThat(first.hasMore()).isTrue();
        assertThat(second.titles())
                .extracting(UserListService.UserAnimeView::title)
                .containsExactly("Naruto");
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void getUserListPageAppliesLocalStatusAndTitleFilters() {
        stubViews();

        UserListService.UserListPage local =
                userListService.getUserListPage("alice", 0, 10, null, null, true, "default");
        UserListService.UserListPage completed =
                userListService.getUserListPage("alice", 0, 10, null, Set.of("completed"), false, "default");
        UserListService.UserListPage ascending =
                userListService.getUserListPage("alice", 0, 10, null, null, false, "title-asc");

        assertThat(local.titles())
                .extracting(UserListService.UserAnimeView::title)
                .containsExactly("Naruto");
        assertThat(completed.titles())
                .extracting(UserListService.UserAnimeView::title)
                .containsExactly("Bleach");
        assertThat(ascending.titles())
                .extracting(UserListService.UserAnimeView::title)
                .containsExactly("Bleach", "Naruto");
    }

    @Test
    void getUserListPageClampsPageAndSize() {
        stubViews();

        UserListService.UserListPage page = userListService.getUserListPage("alice", -5, 0, null, null, false, null);

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(1);
    }

    @Test
    void getUserListPageDoesNotTouchCatalogForEmptyList() {
        when(userAnimeRepository.findByUserId("alice")).thenReturn(List.of());

        UserListService.UserListPage page =
                userListService.getUserListPage("alice", 0, 10, null, null, false, "default");

        assertThat(page.titles()).isEmpty();
        assertThat(page.totalCount()).isZero();
        assertThat(page.hiddenCount()).isZero();
        verifyNoInteractions(catalog);
    }

    @Test
    void getUserListViewReturnsReadyTitlesAndHiddenCount() {
        stubViews();

        UserListService.UserListView view = userListService.getUserListView("alice");

        assertThat(view.titles()).hasSize(2);
        assertThat(view.hiddenCount()).isEqualTo(2);
        assertThat(view.totalCount()).isEqualTo(4);
    }

    @Test
    void getLastImportDelegatesToRepository() {
        UserImport userImport = new UserImport();
        when(userImportRepository.findById("alice")).thenReturn(Optional.of(userImport));

        assertThat(userListService.getLastImport("alice")).contains(userImport);
    }

    private void stubViews() {
        UserAnime watching = entry(1, 1, "watching", 8);
        UserAnime completed = entry(2, 2, "completed", 9);
        UserAnime stub = entry(3, 3, null, null);
        UserAnime pending = entry(4, 4, "planned", 7);

        Anime naruto = anime(1, "Naruto", "Наруто");
        naruto.setCompleteness("full");
        naruto.setPosterHash("hash-1");
        naruto.setPosterExt("png");

        Anime bleach = anime(2, "Bleach", null);
        bleach.setCompleteness("partial");
        bleach.setPosterStatus("no_image");

        Anime unknown = anime(3, "Unknown", null);
        unknown.setCompleteness("stub");

        Anime pendingAnime = anime(4, "Pending", null);
        pendingAnime.setCompleteness("partial");
        pendingAnime.setPosterStatus("pending");

        when(userAnimeRepository.findByUserId("alice")).thenReturn(List.of(watching, completed, stub, pending));
        when(catalog.findAllByIds(any())).thenReturn(Map.of(1L, naruto, 2L, bleach, 3L, unknown, 4L, pendingAnime));
    }

    private static Anime anime(long id, String title, String russian) {
        Anime anime = new Anime();
        anime.setId(id);
        anime.setTitle(title);
        anime.setRussian(russian);
        return anime;
    }

    private static UserAnime entry(long id, long animeId, String status, Integer score) {
        UserAnime entry = new UserAnime();
        entry.setId(id);
        entry.setUserId("alice");
        entry.setAnimeId(animeId);
        entry.setSource("shikimori");
        entry.setStatus(status);
        entry.setScore(score);
        return entry;
    }
}
