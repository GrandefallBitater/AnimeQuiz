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

class YummyAnimeServiceTest {

    private LocalHttpServer http;

    private YummyAnimeService service;

    @BeforeEach
    void setUp() throws IOException {
        http = new LocalHttpServer();
        service = new YummyAnimeService(noRateLimit(), http.baseUrl(), "app-token");
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    @Test
    void sourceNameIsYummy() {
        assertThat(service.sourceName()).isEqualTo("yummy");
    }

    @Test
    void importListResolvesUserAndMapsRatings() {
        http.json("/users/alice", """
                {"response":{"id":5}}""");
        http.json("/users/5/lists", """
                {
                  "response":[
                    {
                      "anime_id":100,
                      "remote_ids":{"shikimori_id":10,"myanimelist_id":20},
                      "user":{"rating":9,"list":{"list":{"id":2}}}
                    },
                    {
                      "anime_id":101,
                      "remote_ids":{"shikimori_id":11},
                      "user":{"list":{"list":{"id":0}}}
                    }
                  ]
                }""");

        AnimeListImporter.ImportResult result = service.importList("alice");

        assertThat(result.userId()).isEqualTo(5L);
        assertThat(result.nickname()).isEqualTo("alice");
        assertThat(result.count()).isEqualTo(2);

        AnimeListImporter.RatingEntry first = result.ratings().get(0);
        assertThat(first.sourceAnimeId()).isEqualTo(100L);
        assertThat(first.shikimoriId()).isEqualTo(10L);
        assertThat(first.malId()).isEqualTo(20L);
        assertThat(first.yummyId()).isEqualTo(100L);
        assertThat(first.sourceRateId()).isEqualTo("100");
        assertThat(first.status()).isEqualTo("completed");
        assertThat(first.score()).isEqualTo(9);
        assertThat(first.episodesWatched()).isNull();
        assertThat(first.rewatches()).isNull();

        AnimeListImporter.RatingEntry second = result.ratings().get(1);
        assertThat(second.malId()).isNull();
        assertThat(second.status()).isEqualTo("watching");
        assertThat(second.score()).isNull();
    }

    @Test
    void cachesNicknameToUserIdBetweenImports() {
        http.json("/users/alice", """
                {"response":{"id":5}}""");
        http.json("/users/5/lists", """
                {"response":[{"anime_id":100}]}""");

        service.importList("alice");
        service.importList("ALICE");

        assertThat(http.hits("/users/alice")).isEqualTo(1);
        assertThat(http.hits("/users/ALICE")).isZero();
        assertThat(http.hits("/users/5/lists")).isEqualTo(2);
    }

    @Test
    void importListRejectsBlankNickname() {
        assertThatThrownBy(() -> service.importList(" ")).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void importListRequiresConfiguredToken() {
        YummyAnimeService withoutToken = new YummyAnimeService(noRateLimit(), http.baseUrl(), "");

        assertThatThrownBy(() -> withoutToken.importList("alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }

    @Test
    void importListReportsMissingUser() {
        http.json("/users/ghost", """
                {"response":{}}""");

        assertThatThrownBy(() -> service.importList("ghost"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void fetchFullMapsTitlesPosterAndNormalizedFields() {
        http.json("/anime/100", """
                {
                  "response":{
                    "anime_id":100,
                    "title":"Наруто",
                    "original":"Naruto",
                    "poster":{"fullsize":"//cdn.test/poster.jpg"},
                    "remote_ids":{"shikimori_id":10,"myanimelist_id":20},
                    "type":{"alias":"TV"},
                    "anime_status":{"alias":"announcement"},
                    "episodes":{"count":220},
                    "anime_url":"naruto"
                  }
                }""");

        AnimeCatalogService.AnimePatch patch = service.fetchFull(100L);

        assertThat(patch.yummyId()).isEqualTo(100L);
        assertThat(patch.shikimoriId()).isEqualTo(10L);
        assertThat(patch.malId()).isEqualTo(20L);
        assertThat(patch.russian()).isEqualTo("Наруто");
        assertThat(patch.name()).isEqualTo("Naruto");
        assertThat(patch.title()).isEqualTo("Наруто");
        assertThat(patch.kind()).isEqualTo("tv");
        assertThat(patch.airingStatus()).isEqualTo("anons");
        assertThat(patch.totalEpisodes()).isEqualTo(220);
        assertThat(patch.url()).isEqualTo("https://yummyani.me/catalog/item/naruto-100");
        assertThat(patch.posterUrl()).isEqualTo("https://cdn.test/poster.jpg");
        assertThat(patch.completeness()).isEqualTo("full");
    }

    @Test
    void fetchByShikimoriIdsReturnsPatchesAndSkipsEmptyRequests() {
        http.json("/anime", """
                {
                  "response":[
                    {
                      "anime_id":100,
                      "title":"Naruto",
                      "original":"Naruto",
                      "type":{"alias":"tv"},
                      "anime_status":{"alias":"released"},
                      "episodes":{"count":220},
                      "anime_url":"naruto"
                    }
                  ]
                }""");

        List<AnimeCatalogService.AnimePatch> patches = service.fetchByShikimoriIds(List.of(10L));

        assertThat(patches).hasSize(1);
        assertThat(patches.get(0).title()).isEqualTo("Naruto");
        assertThat(patches.get(0).name()).isEqualTo("Naruto");
        assertThat(patches.get(0).kind()).isEqualTo("tv");
        assertThat(patches.get(0).airingStatus()).isEqualTo("released");
        assertThat(patches.get(0).totalEpisodes()).isEqualTo(220);
        assertThat(service.fetchByShikimoriIds(List.of())).isEmpty();
        assertThat(http.hits("/anime")).isEqualTo(1);
    }

    @Test
    void fetchByMalIdsUsesTheMalParameter() {
        http.json("/anime", """
                {"response":[{"anime_id":100,"title":"Naruto","anime_url":"naruto"}]}""");

        List<AnimeCatalogService.AnimePatch> patches = service.fetchByMalIds(List.of(20L));

        assertThat(patches).hasSize(1);
        assertThat(patches.get(0).yummyId()).isEqualTo(100L);
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
