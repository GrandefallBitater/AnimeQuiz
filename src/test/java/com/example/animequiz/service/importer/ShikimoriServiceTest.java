package com.example.animequiz.service.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.animequiz.exceptions.InvalidInputException;
import com.example.animequiz.exceptions.NotFoundException;
import com.example.animequiz.service.catalog.AnimeCatalogService;
import com.example.animequiz.service.ratelimit.SourceRateLimiter;
import com.example.animequiz.support.LocalHttpServer;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShikimoriServiceTest {

    private LocalHttpServer http;

    private ShikimoriService service;

    @BeforeEach
    void setUp() throws IOException {
        http = new LocalHttpServer();
        service = new ShikimoriService(noRateLimit(), http.baseUrl() + "/api", http.baseUrl(), "test-agent");
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    @Test
    void sourceNameIsShikimori() {
        assertThat(service.sourceName()).isEqualTo("shikimori");
    }

    @Test
    void importListMapsUserAndRatings() {
        http.json("/api/users/alice", """
                {"id":7,"nickname":"Alice"}""");
        http.json("/api/users/7/anime_rates", """
                [
                  {"id":1,"status":"completed","score":9,"episodes":12,"rewatches":1,
                   "anime":{"id":100,"myanimelist_id":0}},
                  {"id":2,"status":"rewatching","score":8,"episodes":3,"rewatches":0,
                   "anime":{"id":101,"myanimelist_id":500}}
                ]""");

        AnimeListImporter.ImportResult result = service.importList("alice");

        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.nickname()).isEqualTo("Alice");
        assertThat(result.count()).isEqualTo(2);
        assertThat(result.ratings()).hasSize(2);

        AnimeListImporter.RatingEntry first = result.ratings().get(0);
        assertThat(first.sourceAnimeId()).isEqualTo(100L);
        assertThat(first.shikimoriId()).isEqualTo(100L);
        assertThat(first.malId()).isNull();
        assertThat(first.sourceRateId()).isEqualTo("1");
        assertThat(first.status()).isEqualTo("completed");
        assertThat(first.score()).isEqualTo(9);
        assertThat(first.episodesWatched()).isEqualTo(12);
        assertThat(first.rewatches()).isEqualTo(1);

        AnimeListImporter.RatingEntry second = result.ratings().get(1);
        assertThat(second.malId()).isEqualTo(500L);
        assertThat(second.status()).isEqualTo("watching");
    }

    @Test
    void importListRejectsBlankNicknameWithoutRequests() {
        assertThatThrownBy(() -> service.importList("   "))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("Shikimori");

        assertThat(http.hits("/api/users/alice")).isZero();
    }

    @Test
    void importListReportsMissingUser() {
        http.stub("/api/users/ghost", 404, null, new byte[0]);

        assertThatThrownBy(() -> service.importList("ghost"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("не найден");
    }

    @Test
    void importListReportsHiddenAccount() {
        http.stub("/api/users/hidden", 403, null, new byte[0]);

        assertThatThrownBy(() -> service.importList("hidden"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("скрыт");
    }

    @Test
    void fetchFullMapsDetailsAndAbsoluteUrls() {
        http.json("/api/animes/100", """
                {
                  "id":100,
                  "name":"Naruto",
                  "russian":"Наруто",
                  "kind":"tv",
                  "status":"released",
                  "episodes":220,
                  "url":"/animes/100",
                  "myanimelist_id":20,
                  "image":{"original":"/system/animes/original/100.jpg"}
                }""");

        AnimeCatalogService.AnimePatch patch = service.fetchFull(100L);

        assertThat(patch.shikimoriId()).isEqualTo(100L);
        assertThat(patch.malId()).isEqualTo(20L);
        assertThat(patch.name()).isEqualTo("Naruto");
        assertThat(patch.russian()).isEqualTo("Наруто");
        assertThat(patch.title()).isEqualTo("Наруто");
        assertThat(patch.kind()).isEqualTo("tv");
        assertThat(patch.airingStatus()).isEqualTo("released");
        assertThat(patch.totalEpisodes()).isEqualTo(220);
        assertThat(patch.url()).isEqualTo(http.baseUrl() + "/animes/100");
        assertThat(patch.posterUrl()).isEqualTo(http.baseUrl() + "/system/animes/original/100.jpg");
        assertThat(patch.completeness()).isEqualTo("full");
    }

    @Test
    void fetchFullReportsUnknownTitle() {
        http.stub("/api/animes/999", 404, null, new byte[0]);

        assertThatThrownBy(() -> service.fetchFull(999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    void fetchSiteCatalogReturnsPartialTitlesAndSkipsMissingImages() {
        http.json("/api/animes", """
                [
                  {
                    "id":100,
                    "name":"Naruto",
                    "russian":"Наруто",
                    "kind":"tv",
                    "status":"released",
                    "episodes":220,
                    "url":"/animes/100",
                    "image":{"original":"missing_preview.jpg","preview":"/preview/100.jpg"}
                  }
                ]""");

        List<AnimeCatalogService.AnimePatch> patches = service.fetchSiteCatalog();

        assertThat(patches).hasSize(1);
        AnimeCatalogService.AnimePatch patch = patches.get(0);
        assertThat(patch.title()).isEqualTo("Наруто");
        assertThat(patch.posterUrl()).isEqualTo(http.baseUrl() + "/preview/100.jpg");
        assertThat(patch.completeness()).isEqualTo("partial");
    }

    private static SourceRateLimiter noRateLimit() {
        return new SourceRateLimiter() {
            @Override
            public void acquire(String source) {
                // Tests should not wait for the production rate limits.
            }
        };
    }
}
