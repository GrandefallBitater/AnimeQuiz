package com.example.animequiz.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Импорт публичного списка аниме пользователя с Shikimori.
 * <p>
 * OAuth не используется — публичные списки доступны с одним лишь заголовком
 * {@code User-Agent} (он обязателен, иначе IP банится). Соблюдаются лимиты
 * {@code 5rps / 90rpm}. Сеть/DNS на устройстве нестабильны, поэтому каждый
 * запрос повторяется несколько раз, прежде чем считать ресурс недоступным.
 */
@Service
public class ShikimoriService {

    private static final Logger log = LoggerFactory.getLogger(ShikimoriService.class);

    private static final int MAX_ATTEMPTS = 4;      // повтор при сетевых сбоях (в т.ч. DNS)
    private static final long RETRY_DELAY_MS = 800;
    private static final int PAGE_LIMIT = 5000;     // максимум, разрешённый API
    private static final int MAX_PAGES = 20;        // предохранитель от бесконечного цикла

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final String baseUrl;
    private final String siteOrigin;
    private final String userAgent;

    public ShikimoriService(
            @Value("${app.shikimori.base-url}") String baseUrl,
            @Value("${app.shikimori.site-origin}") String siteOrigin,
            @Value("${app.shikimori.user-agent}") String userAgent) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.siteOrigin = siteOrigin.replaceAll("/+$", "");
        this.userAgent = userAgent;
    }

    /** Итог импорта: id/ник на Shikimori, число тайтлов и готовый к сохранению JSON. */
    public record ImportResult(long userId, String nickname, int count, String json) {
    }

    public ImportResult importAnimeList(String nickname) {
        String clean = nickname == null ? "" : nickname.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Укажите никнейм на Shikimori");
        }

        // ник -> числовой id
        JsonNode user = getJson("/users/" + enc(clean) + "?is_nickname=1");
        if (user == null || !user.hasNonNull("id")) {
            throw new ShikimoriException("Пользователь \"" + clean + "\" не найден на Shikimori");
        }
        long userId = user.get("id").asLong();
        String resolvedNick = user.path("nickname").asText(clean);

        // список аниме постранично
        ArrayNode titles = mapper.createArrayNode();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode rates = getJson("/users/" + userId + "/anime_rates?limit=" + PAGE_LIMIT + "&page=" + page);
            if (rates == null || !rates.isArray() || rates.isEmpty()) {
                break;
            }
            for (JsonNode rate : rates) {
                titles.add(projectTitle(rate));
            }
            if (rates.size() < PAGE_LIMIT) {
                break;
            }
            sleep(250); // бережём лимит 5rps
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("nickname", resolvedNick);
        payload.put("userId", userId);
        payload.put("source", "shikimori.io");
        payload.put("importedAt", Instant.now().toString());
        payload.put("count", titles.size());
        payload.set("titles", titles);

        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            return new ImportResult(userId, resolvedNick, titles.size(), json);
        } catch (IOException e) {
            throw new ShikimoriException("Не удалось сериализовать список тайтлов", e);
        }
    }

    /** Оставляем только нужные поля тайтла. */
    private ObjectNode projectTitle(JsonNode rate) {
        JsonNode anime = rate.path("anime");
        String name = asTextOrNull(anime, "name");
        String russian = asTextOrNull(anime, "russian");
        ObjectNode t = mapper.createObjectNode();
        t.put("rateId", rate.path("id").asLong());
        t.put("status", asTextOrNull(rate, "status"));
        t.put("score", rate.path("score").asInt(0));
        t.put("episodes", rate.path("episodes").asInt(0));
        t.put("rewatches", rate.path("rewatches").asInt(0));
        t.put("animeId", anime.path("id").asLong());
        t.put("name", name);
        t.put("russian", russian);
        // готовое к показу название: русское, иначе оригинальное
        t.put("title", (russian != null && !russian.isBlank()) ? russian : name);
        t.put("kind", asTextOrNull(anime, "kind"));
        t.put("animeStatus", asTextOrNull(anime, "status"));
        t.put("totalEpisodes", anime.path("episodes").asInt(0));
        t.put("url", absolute(asTextOrNull(anime, "url")));
        t.put("imageUrl", absolute(anime.path("image").path("preview").asText(null)));
        return t;
    }

    private static String asTextOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /** Ссылки/картинки в API относительные — приводим к абсолютным. */
    private String absolute(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            return null;
        }
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return pathOrUrl;
        }
        return siteOrigin + (pathOrUrl.startsWith("/") ? "" : "/") + pathOrUrl;
    }

    /**
     * GET c ретраями. {@code null} — если ресурс отсутствует (404).
     * Бросает {@link ShikimoriException}, если после всех попыток связи нет.
     */
    private JsonNode getJson(String path) {
        String url = baseUrl + path;
        IOException lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", userAgent)   // обязателен: без него бан по IP
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = resp.statusCode();

                if (code == 404) {
                    return null;
                }
                if (code == 429) { // превышен лимит запросов — ждём подольше
                    log.warn("Shikimori 429 (rate limit) на {}, попытка {}/{}", path, attempt, MAX_ATTEMPTS);
                    sleep(RETRY_DELAY_MS * attempt * 2L);
                    continue;
                }
                if (code >= 500) {
                    log.warn("Shikimori {} на {}, попытка {}/{}", code, path, attempt, MAX_ATTEMPTS);
                    sleep(RETRY_DELAY_MS * attempt);
                    continue;
                }
                if (code != 200) {
                    throw new ShikimoriException("Shikimori ответил кодом " + code);
                }
                return mapper.readTree(resp.body());

            } catch (IOException e) {
                // сюда попадают UnknownHostException (нестабильный DNS), таймауты и обрывы
                lastError = e;
                log.warn("Сеть недоступна при обращении к Shikimori ({}), попытка {}/{}: {}",
                        path, attempt, MAX_ATTEMPTS, e.toString());
                sleep(RETRY_DELAY_MS * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ShikimoriException("Импорт прерван", e);
            }
        }
        throw new ShikimoriException(
                "Shikimori недоступен: проверьте соединение и повторите попытку", lastError);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static class ShikimoriException extends RuntimeException {
        public ShikimoriException(String message) {
            super(message);
        }

        public ShikimoriException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
