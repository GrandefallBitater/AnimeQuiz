package com.example.animequiz.service.importer;

import com.example.animequiz.exceptions.ExternalServiceException;
import com.example.animequiz.exceptions.InvalidInputException;
import com.example.animequiz.exceptions.NotFoundException;
import com.example.animequiz.service.catalog.AnimeCatalogService;
import com.example.animequiz.service.ratelimit.SourceRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ShikimoriService implements AnimeListImporter {

    private static final int MAX_ATTEMPTS = 4;
    private static final long RETRY_DELAY_MS = 3200;
    private static final int PAGE_LIMIT = 50;
    private static final int MAX_PAGES = 500;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final SourceRateLimiter rateLimiter;

    private final String baseUrl;
    private final String siteOrigin;
    private final String userAgent;

    public ShikimoriService(
            SourceRateLimiter rateLimiter,
            @Value("${app.shikimori.base-url}") String baseUrl,
            @Value("${app.shikimori.site-origin}") String siteOrigin,
            @Value("${app.shikimori.user-agent}") String userAgent) {
        this.rateLimiter = rateLimiter;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.siteOrigin = siteOrigin.replaceAll("/+$", "");
        this.userAgent = userAgent;
    }

    @Override
    public String sourceName() {
        return "shikimori";
    }

    @Override
    public ImportResult importList(String nickname) {
        return importList(nickname, () -> {});
    }

    @Override
    public ImportResult importList(String nickname, Runnable onProgress) {
        String clean = nickname == null ? "" : nickname.trim();
        if (clean.isEmpty()) throw new InvalidInputException("Укажите никнейм на Shikimori");

        JsonNode user = getJson("/users/" + enc(clean) + "?is_nickname=1");
        if (user == null || !user.hasNonNull("id")) {
            throw new NotFoundException("Пользователь \"" + clean + "\" не найден на Shikimori");
        }
        long userId = user.get("id").asLong();
        String resolvedNick = user.path("nickname").asText(clean);

        ArrayNode rates = fetchAllPages(page -> {
            onProgress.run();
            return getJson("/users/" + userId + "/anime_rates?limit=" + PAGE_LIMIT + "&page=" + page);
        });

        List<RatingEntry> entries = new ArrayList<>(rates.size());
        for (JsonNode rate : rates) {
            JsonNode anime = rate.path("anime");
            long animeId = anime.path("id").asLong();
            Long malId = anime.hasNonNull("myanimelist_id")
                    ? anime.get("myanimelist_id").asLong()
                    : null;
            if (malId != null && malId == 0) malId = null;

            entries.add(new RatingEntry(
                    animeId,
                    animeId,
                    malId,
                    null,
                    String.valueOf(rate.path("id").asLong()),
                    mapStatus(rate.path("status").asText(null)),
                    rate.path("score").asInt(0),
                    rate.path("episodes").asInt(0),
                    rate.path("rewatches").asInt(0)));
        }

        log.info("Shikimori: {} → {} записей", resolvedNick, entries.size());
        return new ImportResult(userId, resolvedNick, entries.size(), entries);
    }

    private static String mapStatus(String shiki) {
        if (shiki == null) return null;
        return switch (shiki) {
            case "watching", "rewatching" -> "watching";
            case "completed" -> "completed";
            case "on_hold" -> "on_hold";
            case "dropped" -> "dropped";
            case "planned" -> "planned";
            default -> null;
        };
    }

    @Override
    public AnimeCatalogService.AnimePatch fetchFull(long shikimoriId) {
        JsonNode detail = getJson("/animes/" + shikimoriId);
        if (detail == null || detail.isMissingNode()) {
            throw new NotFoundException("Shikimori не знает тайтл " + shikimoriId);
        }
        JsonNode imageNode = detail.path("image");
        String posterUrl = pickImageUrl(imageNode);
        Long malId = detail.hasNonNull("myanimelist_id")
                ? detail.get("myanimelist_id").asLong()
                : null;
        if (malId != null && malId == 0) malId = null;

        String name = asTextOrNull(detail, "name");
        String russian = asTextOrNull(detail, "russian");

        return AnimeCatalogService.AnimePatch.builder()
                .shikimoriId(shikimoriId)
                .malId(malId)
                .name(name)
                .russian(russian)
                .title(firstNonBlank(russian, name))
                .kind(asTextOrNull(detail, "kind"))
                .airingStatus(asTextOrNull(detail, "status"))
                .totalEpisodes(detail.path("episodes").asInt(0))
                .url(absolute(asTextOrNull(detail, "url")))
                .posterUrl(posterUrl == null ? null : absolute(posterUrl))
                .completeness("full")
                .build();
    }

    public List<AnimeCatalogService.AnimePatch> fetchSiteCatalog() {
        ArrayNode titles = fetchAllPages(page -> getJson("/animes?limit=" + PAGE_LIMIT + "&page=" + page));
        List<AnimeCatalogService.AnimePatch> result = new ArrayList<>(titles.size());
        for (JsonNode anime : titles) {
            long id = anime.path("id").asLong();
            String posterUrl = pickImageUrl(anime.path("image"));
            Long malId = anime.hasNonNull("myanimelist_id")
                    ? anime.get("myanimelist_id").asLong()
                    : null;
            if (malId != null && malId == 0) malId = null;

            String name = asTextOrNull(anime, "name");
            String russian = asTextOrNull(anime, "russian");

            result.add(AnimeCatalogService.AnimePatch.builder()
                    .shikimoriId(id)
                    .malId(malId)
                    .name(name)
                    .russian(russian)
                    .title(firstNonBlank(russian, name))
                    .kind(asTextOrNull(anime, "kind"))
                    .airingStatus(asTextOrNull(anime, "status"))
                    .totalEpisodes(anime.path("episodes").asInt(0))
                    .url(absolute(asTextOrNull(anime, "url")))
                    .posterUrl(posterUrl == null ? null : absolute(posterUrl))
                    .completeness("partial")
                    .build());
        }
        return result;
    }

    private ArrayNode fetchAllPages(IntFunction<JsonNode> pageFetcher) {
        ArrayNode titles = mapper.createArrayNode();
        for (int page = 1; page <= MAX_PAGES; page++) {
            if (page % 20 == 0) log.info("Shikimori: страница {}", page);
            JsonNode rates = pageFetcher.apply(page);
            if (rates == null || !rates.isArray() || rates.isEmpty()) break;
            for (JsonNode rate : rates) titles.add(rate);
            if (rates.size() < PAGE_LIMIT) break;
        }
        return titles;
    }

    private static String pickImageUrl(JsonNode imageNode) {
        if (imageNode == null || imageNode.isMissingNode() || imageNode.isNull()) return null;
        for (String f : new String[] {"original", "preview", "x96", "x48"}) {
            String v = imageNode.path(f).asText(null);
            if (v != null && !v.isBlank() && !v.contains("missing_")) return v;
        }
        return null;
    }

    private static String asTextOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String firstNonBlank(String... s) {
        for (String v : s) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private String absolute(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) return null;
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return pathOrUrl;
        return siteOrigin + (pathOrUrl.startsWith("/") ? "" : "/") + pathOrUrl;
    }

    private JsonNode getJson(String path) {
        rateLimiter.acquire("shikimori");
        return doGetJson(path);
    }

    private JsonNode doGetJson(String path) {
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
                if (code == 404) return null;
                if (code == 429) {
                    sleep(RETRY_DELAY_MS * attempt * 2L);
                    continue;
                }
                if (code >= 500) {
                    sleep(RETRY_DELAY_MS * attempt);
                    continue;
                }
                if (code == 403) {
                    throw new NotFoundException("Аккаунт скрыт");
                }
                if (code != 200) {
                    log.error(
                            "Shikimori вернул ошибку. Статус: {}. URL: {}. Ответ: {}",
                            resp.statusCode(),
                            url,
                            resp.body());
                    throw new ExternalServiceException("Shikimori вернул ошибку");
                }
                return mapper.readTree(resp.body());
            } catch (IOException e) {
                lastError = e;
                sleep(RETRY_DELAY_MS * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExternalServiceException("Импорт прерван", e);
            }
        }
        throw new ExternalServiceException("Shikimori недоступен", lastError);
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
}
