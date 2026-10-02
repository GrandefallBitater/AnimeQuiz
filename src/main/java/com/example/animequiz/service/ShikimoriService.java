package com.example.animequiz.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

@Slf4j
@Service
public class ShikimoriService {

    private static final int MAX_ATTEMPTS = 4;
    private static final long RETRY_DELAY_MS = 3200;
    private static final int PAGE_LIMIT = 50;
    private static final int MAX_PAGES = 500;

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
    
    //TODO we should block user movement when user importing animeList
    @PreAuthorize("hasRole('ADMIN')")
    public byte[] importSiteAnimeList() {
        ArrayNode titles = fetchAllPages(page -> getJson(
                "/animes?limit=" + PAGE_LIMIT + "&page=" + page));

        sortTitlesRu(titles);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("source", "shikimori.io");
        payload.put("importedAt", Instant.now().toString());
        payload.put("count", titles.size());
        payload.set("titles", titles);

        log.info("Импорт окончен ({} тайтлов)", titles.size());
        return serialize(payload);
    }

    public record ImportResult(long userId, String nickname, int count, String json) {}

    @SuppressWarnings("LoggingSimilarMessage")
    public ImportResult importAnimeList(String nickname) {
        String clean = nickname == null ? "" : nickname.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Укажите никнейм на Shikimori");
        }
        JsonNode user = getJson("/users/" + enc(clean) + "?is_nickname=1");
        if (user == null || !user.hasNonNull("id")) {
            throw new ShikimoriException("Пользователь \"" + clean + "\" не найден на Shikimori");
        }
        long userId = user.get("id").asLong();
        String resolvedNick = user.path("nickname").asText(clean);

        ArrayNode titles = fetchAllPages(page -> getJson(
                "/users/" + userId + "/anime_rates?limit=" + PAGE_LIMIT + "&page=" + page));

        sortTitlesRu(titles);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("nickname", resolvedNick);
        payload.put("userId", userId);
        payload.put("source", "shikimori.io");
        payload.put("importedAt", Instant.now().toString());
        payload.put("count", titles.size());
        payload.set("titles", titles);

        log.info("Импорт окончен ({} тайтлов)", titles.size());
        String json = serializeToString(payload);
        return new ImportResult(userId, resolvedNick, titles.size(), json);
    }

    private ArrayNode fetchAllPages(IntFunction<JsonNode> pageFetcher) {
        ArrayNode titles = mapper.createArrayNode();
        for (int page = 1; page <= MAX_PAGES; page++) {
            if (page % 20 == 0) {
                log.info("Обработка страницы {}", page);
            }
            JsonNode rates = pageFetcher.apply(page);
            if (rates == null || !rates.isArray() || rates.isEmpty()) {
                break;
            }
            for (JsonNode rate : rates) {
                titles.add(projectTitle(rate));
            }
            if (rates.size() < PAGE_LIMIT) {
                break;
            }
            sleep(250);
        }
        return titles;
    }

    private void sortTitlesRu(ArrayNode titles) {
        List<JsonNode> list = new ArrayList<>(titles.size());
        titles.forEach(list::add);

        Collator ru = Collator.getInstance(new Locale("ru", "RU"));
        ru.setStrength(Collator.PRIMARY);

        list.sort((a, b) -> {
            String keyA = normalizeSortKey(a);
            String keyB = normalizeSortKey(b);

            boolean aLetter = startsWithLetter(keyA);
            boolean bLetter = startsWithLetter(keyB);

            if (aLetter != bLetter) {
                return aLetter ? -1 : 1;
            }
            return ru.compare(keyA, keyB);
        });

        titles.removeAll();
        titles.addAll(list);
    }

    private String normalizeSortKey(JsonNode n) {
        String russian = n.path("russian").asText("").strip();
        String name = n.path("name").asText("").strip();
        String key = russian.isEmpty() ? name : russian;
        String normalized = key.replaceFirst("^[^\\p{L}\\p{N}]+", "");
        return normalized.isEmpty() ? key : normalized;
    }

    private boolean startsWithLetter(String s) {
        if (s == null || s.isEmpty()) return false;
        return Character.isLetter(s.codePointAt(0));
    }

    private byte[] serialize(ObjectNode payload) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
        } catch (IOException e) {
            throw new ShikimoriException("Не удалось сериализовать список тайтлов", e);
        }
    }

    private String serializeToString(ObjectNode payload) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (IOException e) {
            throw new ShikimoriException("Не удалось сериализовать список тайтлов", e);
        }
    }

    private ObjectNode projectTitle(JsonNode rate) {
        JsonNode anime = rate.has("anime") ? rate.path("anime") : rate;

        String name = asTextOrNull(anime, "name");
        String russian = asTextOrNull(anime, "russian");

        ObjectNode t = mapper.createObjectNode();

        t.put("rateId", rate.has("anime") ? rate.path("id").asLong() : 0L);
        t.put("status", rate.has("anime") ? asTextOrNull(rate, "status") : null);
        t.put("score", rate.has("anime") ? rate.path("score").asInt(0) : 0);
        t.put("episodes", rate.has("anime") ? rate.path("episodes").asInt(0) : 0);
        t.put("rewatches", rate.has("anime") ? rate.path("rewatches").asInt(0) : 0);

        t.put("animeId", anime.path("id").asLong());
        t.put("name", name);
        t.put("russian", russian);
        t.put("title", (russian != null && !russian.isBlank()) ? russian : name);
        t.put("kind", asTextOrNull(anime, "kind"));
        t.put("animeStatus", asTextOrNull(anime, "status"));
        t.put("totalEpisodes", anime.path("episodes").asInt(0));
        t.put("url", absolute(asTextOrNull(anime, "url")));

        JsonNode imageNode = anime.path("image");
        String previewUrl = imageNode.isMissingNode() ? null : imageNode.path("preview").asText(null);
        t.put("imageUrl", absolute(previewUrl));

        return t;
    }

    private static String asTextOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private String absolute(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            return null;
        }
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return pathOrUrl;
        }
        return siteOrigin + (pathOrUrl.startsWith("/") ? "" : "/") + pathOrUrl;
    }

    private JsonNode getJson(String path) {
        String url = baseUrl + path;
        IOException lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", userAgent)
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = resp.statusCode();

                if (code == 404) {
                    return null;
                }
                if (code == 429) {
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