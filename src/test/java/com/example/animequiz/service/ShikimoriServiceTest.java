package com.example.animequiz.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShikimoriServiceTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static WireMockServer wm;

    private ShikimoriService service;

    @BeforeAll
    static void startWireMock() {
        wm = new WireMockServer(wireMockConfig().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stopWireMock() {
        wm.stop();
    }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        service = new ShikimoriService(
                "http://localhost:" + wm.port(),
                "https://shikimori.io",
                "AnimeQuiz Test Agent");
    }

    @Test
    void importAnimeListReturnsProjectedList() throws Exception {
        stubUser("TestUser", 42);
        stubRatesPage(42, 1, """
                [{
                  "id": 111, "status": "watching", "score": 8, "episodes": 5, "rewatches": 1,
                  "anime": {
                    "id": 9253, "name": "Steins;Gate", "russian": "Врата Штейна",
                    "kind": "tv", "status": "released", "episodes": 24,
                    "url": "/animes/9253-steins-gate",
                    "image": {"preview": "/system/animes/preview/9253.jpg"}
                  }
                }]
                """);

        ShikimoriService.ImportResult result = service.importAnimeList("TestUser");

        assertThat(result.userId()).isEqualTo(42);
        assertThat(result.nickname()).isEqualTo("TestUser");
        assertThat(result.count()).isEqualTo(1);

        JsonNode json = mapper.readTree(result.json());
        assertThat(json.path("source").asText()).isEqualTo("shikimori.io");
        assertThat(json.path("count").asInt()).isEqualTo(1);
        assertThat(json.path("importedAt").asText()).isNotBlank();

        JsonNode title = json.path("titles").get(0);
        assertThat(title.path("rateId").asLong()).isEqualTo(111);
        assertThat(title.path("animeId").asLong()).isEqualTo(9253);
        assertThat(title.path("status").asText()).isEqualTo("watching");
        assertThat(title.path("score").asInt()).isEqualTo(8);
        assertThat(title.path("episodes").asInt()).isEqualTo(5);
        assertThat(title.path("totalEpisodes").asInt()).isEqualTo(24);
        assertThat(title.path("title").asText()).isEqualTo("Врата Штейна");
        assertThat(title.path("url").asText()).isEqualTo("https://shikimori.io/animes/9253-steins-gate");
        assertThat(title.path("imageUrl").asText())
                .isEqualTo("https://shikimori.io/system/animes/preview/9253.jpg");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/users/42/anime_rates"))
                .withQueryParam("page", equalTo("1")));
        wm.verify(0, getRequestedFor(urlPathEqualTo("/users/42/anime_rates"))
                .withQueryParam("page", equalTo("2")));
    }

    @Test
    void importAnimeListFetchesSeveralPagesUntilLessThanLimit() throws Exception {
        stubUser("TestUser", 42);
        stubRatesPage(42, 1, ratesPageBody(50, 1));
        stubRatesPage(42, 2, ratesPageBody(20, 100));

        ShikimoriService.ImportResult result = service.importAnimeList("TestUser");

        assertThat(result.count()).isEqualTo(70);
        wm.verify(getRequestedFor(urlPathEqualTo("/users/42/anime_rates"))
                .withQueryParam("page", equalTo("2")));
        wm.verify(0, getRequestedFor(urlPathEqualTo("/users/42/anime_rates"))
                .withQueryParam("page", equalTo("3")));
    }

    @Test
    void importAnimeListFallsBackToNameWhenRussianIsEmpty() throws Exception {
        stubUser("TestUser", 42);
        stubRatesPage(42, 1, """
                [{"id":1,"status":"completed","score":9,"episodes":12,"rewatches":0,
                  "anime":{"id":7,"name":"Only Name","russian":null,"kind":"tv",
                           "status":"released","episodes":12,"url":"/animes/7",
                           "image":{"preview":"/img/7.jpg"}}}]
                """);

        ShikimoriService.ImportResult result = service.importAnimeList("TestUser");

        JsonNode title = mapper.readTree(result.json()).path("titles").get(0);
        assertThat(title.path("title").asText()).isEqualTo("Only Name");
    }

    @Test
    void importAnimeListHandlesMissingImageAndAbsoluteUrls() throws Exception {
        stubUser("TestUser", 42);
        stubRatesPage(42, 1, """
                [
                  {"id":1,"status":"completed","score":9,"episodes":12,"rewatches":0,
                   "anime":{"id":1,"name":"A","russian":null,"kind":"tv","status":"released",
                            "episodes":12,"url":"https://shikimori.io/animes/1-a"}},
                  {"id":2,"status":"planned","score":0,"episodes":0,"rewatches":0,
                   "anime":{"id":2,"name":"B","russian":"Б","kind":"movie","status":"anons",
                            "episodes":1,"url":"/animes/2-b",
                            "image":{"preview":"https://cdn.example/2.jpg"}}}
                ]
                """);

        ShikimoriService.ImportResult result = service.importAnimeList("TestUser");

        JsonNode titles = mapper.readTree(result.json()).path("titles");
        // у первого аниме нет image -> imageUrl = null, url уже абсолютный
        assertThat(titles.get(0).path("imageUrl").isNull()).isTrue();
        assertThat(titles.get(0).path("url").asText()).isEqualTo("https://shikimori.io/animes/1-a");
        // у второго абсолютный preview не дополняется ориджином
        assertThat(titles.get(1).path("imageUrl").asText()).isEqualTo("https://cdn.example/2.jpg");
        assertThat(titles.get(1).path("title").asText()).isEqualTo("Б");
    }

    @Test
    void importAnimeListThrowsWhenUserNotFound() {
        wm.stubFor(get(urlEqualTo("/users/Ghost?is_nickname=1")).willReturn(notFound()));

        assertThatThrownBy(() -> service.importAnimeList("Ghost"))
                .isInstanceOf(ShikimoriService.ShikimoriException.class)
                .hasMessageContaining("не найден");
    }

    @Test
    void importAnimeListRejectsBlankNickname() {
        assertThatThrownBy(() -> service.importAnimeList(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.importAnimeList("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void importAnimeListTrimsNickname() throws Exception {
        stubUser("Nick", 7);
        stubRatesPage(7, 1, "[]");

        ShikimoriService.ImportResult result = service.importAnimeList("  Nick  ");

        assertThat(result.userId()).isEqualTo(7);
        assertThat(result.count()).isZero();
    }

    @Test
    void importAnimeListFailsOnUnexpectedStatus() {
        wm.stubFor(get(urlEqualTo("/users/Bad?is_nickname=1"))
                .willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> service.importAnimeList("Bad"))
                .isInstanceOf(ShikimoriService.ShikimoriException.class)
                .hasMessageContaining("403");
    }

    @Test
    void importAnimeListRetriesOn429() throws Exception {
        wm.stubFor(get(urlEqualTo("/users/Retry?is_nickname=1"))
                .inScenario("rate-limit")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("ok"));
        wm.stubFor(get(urlEqualTo("/users/Retry?is_nickname=1"))
                .inScenario("rate-limit")
                .whenScenarioStateIs("ok")
                .willReturn(okJson("{\"id\":9,\"nickname\":\"Retry\"}")));
        wm.stubFor(get(urlPathEqualTo("/users/9/anime_rates")).willReturn(okJson("[]")));

        ShikimoriService.ImportResult result = service.importAnimeList("Retry");

        assertThat(result.userId()).isEqualTo(9);
        wm.verify(2, getRequestedFor(urlEqualTo("/users/Retry?is_nickname=1")));
    }

    @Test
    void importSiteAnimeListCollectsPagesFromCatalog() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/animes"))
                .withQueryParam("limit", equalTo("50"))
                .withQueryParam("page", equalTo("1"))
                .willReturn(okJson(sitePageBody(50, 1))));
        wm.stubFor(get(urlPathEqualTo("/animes"))
                .withQueryParam("limit", equalTo("50"))
                .withQueryParam("page", equalTo("2"))
                .willReturn(okJson(sitePageBody(3, 200))));

        byte[] bytes = service.importSiteAnimeList();
        JsonNode json = mapper.readTree(bytes);

        assertThat(json.path("source").asText()).isEqualTo("shikimori.io");
        assertThat(json.path("count").asInt()).isEqualTo(53);
        assertThat(json.path("titles").isArray()).isTrue();
        JsonNode first = json.path("titles").get(0);
        assertThat(first.path("rateId").asLong()).isZero();
        assertThat(first.path("animeId").asLong()).isEqualTo(1);
        assertThat(first.path("name").asText()).isEqualTo("Anime 1");
    }

    @Test
    void importSiteAnimeListRetriesOnServerError() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/animes")).withQueryParam("page", equalTo("1"))
                .inScenario("server-error")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"));
        wm.stubFor(get(urlPathEqualTo("/animes")).withQueryParam("page", equalTo("1"))
                .inScenario("server-error")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(sitePageBody(2, 1))));

        byte[] bytes = service.importSiteAnimeList();

        assertThat(mapper.readTree(bytes).path("count").asInt()).isEqualTo(2);
        wm.verify(2, getRequestedFor(urlPathEqualTo("/animes"))
                .withQueryParam("page", equalTo("1")));
    }

    private void stubUser(String nick, long id) {
        wm.stubFor(get(urlEqualTo("/users/" + nick + "?is_nickname=1"))
                .willReturn(okJson("{\"id\":" + id + ",\"nickname\":\"" + nick + "\"}")));
    }

    private void stubRatesPage(long userId, int page, String body) {
        wm.stubFor(get(urlPathEqualTo("/users/" + userId + "/anime_rates"))
                .withQueryParam("limit", equalTo("50"))
                .withQueryParam("page", equalTo(String.valueOf(page)))
                .willReturn(okJson(body)));
    }

    private String ratesPageBody(int count, long startId) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            long id = startId + i;
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(id)
                    .append(",\"status\":\"completed\",\"score\":7,\"episodes\":12,\"rewatches\":0,")
                    .append("\"anime\":{\"id\":").append(id)
                    .append(",\"name\":\"Anime ").append(id).append("\",\"russian\":null,")
                    .append("\"kind\":\"tv\",\"status\":\"released\",\"episodes\":12,")
                    .append("\"url\":\"/animes/").append(id)
                    .append("\",\"image\":{\"preview\":\"/img/").append(id).append(".jpg\"}}}");
        }
        return sb.append(']').toString();
    }

    private String sitePageBody(int count, long startId) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            long id = startId + i;
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(id)
                    .append(",\"name\":\"Anime ").append(id).append("\",\"russian\":null,")
                    .append("\"kind\":\"tv\",\"status\":\"released\",\"episodes\":12,")
                    .append("\"url\":\"/animes/").append(id)
                    .append("\",\"image\":{\"preview\":\"/img/").append(id).append(".jpg\"}}");
        }
        return sb.append(']').toString();
    }
}