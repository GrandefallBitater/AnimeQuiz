package com.example.animequiz.service.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.animequiz.exceptions.InvalidInputException;
import com.example.animequiz.exceptions.NotFoundException;
import com.example.animequiz.service.catalog.AnimeCatalogService;
import com.example.animequiz.service.ratelimit.SourceRateLimiter;
import com.example.animequiz.support.LocalHttpServer;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MalServiceTest {

    private LocalHttpServer http;

    private MalService service;

    @BeforeEach
    void setUp() throws IOException {
        http = new LocalHttpServer();
        service = new MalService(http.baseUrl(), http.baseUrl() + "/v2", "client-id", noRateLimit());
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    @Test
    void sourceNameIsMyAnimeList() {
        assertThat(service.sourceName()).isEqualTo("myanimelist");
    }

    @Test
    void importListMapsStatusesAndSkipsInvalidIds() {
        http.json("/animelist/alice/load.json", """
                [
                  {"anime_id":100,"status":2,"score":9,"num_watched_episodes":12,"is_rewatching":false},
                  {"anime_id":101,"status":1,"score":8,"num_watched_episodes":3,"is_rewatching":true},
                  {"anime_id":0,"status":1}
                ]""");

        AnimeListImporter.ImportResult result = service.importList("alice");

        assertThat(result.userId()).isZero();
        assertThat(result.nickname()).isEqualTo("alice");
        assertThat(result.count()).isEqualTo(2);
        assertThat(result.ratings()).hasSize(2);

        AnimeListImporter.RatingEntry first = result.ratings().get(0);
        assertThat(first.sourceAnimeId()).isEqualTo(100L);
        assertThat(first.malId()).isEqualTo(100L);
        assertThat(first.shikimoriId()).isNull();
        assertThat(first.sourceRateId()).isEqualTo("100");
        assertThat(first.status()).isEqualTo("completed");
        assertThat(first.score()).isEqualTo(9);
        assertThat(first.episodesWatched()).isEqualTo(12);
        assertThat(first.rewatches()).isZero();

        AnimeListImporter.RatingEntry second = result.ratings().get(1);
        assertThat(second.status()).isEqualTo("watching");
        assertThat(second.rewatches()).isEqualTo(1);
    }

    @Test
    void importListRejectsBlankNickname() {
        assertThatThrownBy(() -> service.importList(" ")).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void importListReportsMissingUserWhenNothingWasLoaded() {
        http.stub("/animelist/ghost/load.json", 404, null, new byte[0]);

        assertThatThrownBy(() -> service.importList("ghost"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("не найден");
    }

    @Test
    void fetchFullMapsApiFields() {
        http.json("/v2/anime/100", """
                {
                  "id":100,
                  "title":"Naruto",
                  "main_picture":{"large":"https://cdn.test/large.jpg","medium":"https://cdn.test/medium.jpg"},
                  "media_type":"TV",
                  "num_episodes":220,
                  "status":"finished_airing"
                }""");

        AnimeCatalogService.AnimePatch patch = service.fetchFull(100L);

        assertThat(patch.malId()).isEqualTo(100L);
        assertThat(patch.name()).isEqualTo("Naruto");
        assertThat(patch.title()).isEqualTo("Naruto");
        assertThat(patch.kind()).isEqualTo("tv");
        assertThat(patch.airingStatus()).isEqualTo("released");
        assertThat(patch.totalEpisodes()).isEqualTo(220);
        assertThat(patch.url()).isEqualTo("https://myanimelist.net/anime/100");
        assertThat(patch.posterUrl()).isEqualTo("https://cdn.test/large.jpg");
        assertThat(patch.completeness()).isEqualTo("partial");
    }

    @Test
    void fetchFullFallsBackToMediumPoster() {
        http.json("/v2/anime/100", """
                {
                  "id":100,
                  "title":"Naruto",
                  "main_picture":{"medium":"https://cdn.test/medium.jpg"},
                  "media_type":"Movie",
                  "num_episodes":1,
                  "status":"currently_airing"
                }""");

        AnimeCatalogService.AnimePatch patch = service.fetchFull(100L);

        assertThat(patch.posterUrl()).isEqualTo("https://cdn.test/medium.jpg");
        assertThat(patch.kind()).isEqualTo("movie");
        assertThat(patch.airingStatus()).isEqualTo("ongoing");
    }

    @Test
    void fetchFullRequiresClientId() {
        MalService withoutToken = new MalService(http.baseUrl(), http.baseUrl() + "/v2", "", noRateLimit());

        assertThatThrownBy(() -> withoutToken.fetchFull(100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-id");
    }

    @Test
    void fetchFullReportsUnknownTitle() {
        http.stub("/v2/anime/999", 404, null, new byte[0]);

        assertThatThrownBy(() -> service.fetchFull(999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("999");
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
