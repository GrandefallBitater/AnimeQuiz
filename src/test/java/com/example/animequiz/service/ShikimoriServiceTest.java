package com.example.animequiz.service;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@WireMockTest
class ShikimoriServiceTest {

    private ShikimoriService shikimoriService;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        String wireMockBaseUrl = wmRuntimeInfo.getHttpBaseUrl();
        shikimoriService = new ShikimoriService(
                wireMockBaseUrl,
                "https://shikimori.me",
                "TestUserAgent"
        );
    }

    @Test
    void shouldSuccessfullyImportAnimeList() {
        stubFor(get(urlEqualTo("/users/Sm0key?is_nickname=1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\": 12345, \"nickname\": \"Sm0key\"}")));

        stubFor(get(urlEqualTo("/users/12345/anime_rates?limit=5000&page=1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {
                                    "id": 999,
                                    "status": "watching",
                                    "score": 9,
                                    "episodes": 12,
                                    "rewatches": 0,
                                    "anime": {
                                      "id": 5114,
                                      "name": "Fullmetal Alchemist: Brotherhood",
                                      "russian": "Стальной алхимик: Братство",
                                      "kind": "tv",
                                      "status": "released",
                                      "episodes": 64,
                                      "url": "/animes/5114-fullmetal-alchemist-brotherhood",
                                      "image": {
                                        "preview": "/system/animes/preview/5114.jpg"
                                      }
                                    }
                                  }
                                ]
                                """)));

        ShikimoriService.ImportResult result = shikimoriService.importAnimeList("Sm0key");

        assertThat(result).isNotNull();
        assertThat(result.userId()).isEqualTo(12345L);
        assertThat(result.nickname()).isEqualTo("Sm0key");
        assertThat(result.count()).isEqualTo(1);
        assertThat(result.json()).contains("Стальной алхимик: Братство");
    }

    @Test
    void shouldThrowExceptionWhenUserNotFound() {
        stubFor(get(urlEqualTo("/users/UnknownUser?is_nickname=1"))
                .willReturn(aResponse().withStatus(404)));

        ShikimoriService.ShikimoriException exception = assertThrows(
                ShikimoriService.ShikimoriException.class,
                () -> shikimoriService.importAnimeList("UnknownUser")
        );

        assertThat(exception.getMessage()).contains("не найден на Shikimori");
    }

    @Test
    void shouldThrowExceptionWhenUserJsonIsInvalid() {
        stubFor(get(urlEqualTo("/users/InvalidUser?is_nickname=1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"nickname\": \"InvalidUser\"}")));
        ShikimoriService.ShikimoriException exception = assertThrows(
                ShikimoriService.ShikimoriException.class,
                () -> shikimoriService.importAnimeList("InvalidUser")
        );

        assertThat(exception.getMessage()).contains("не найден на Shikimori");
    }
}