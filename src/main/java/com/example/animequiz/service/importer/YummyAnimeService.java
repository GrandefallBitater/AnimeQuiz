package com.example.animequiz.service.importer;

import com.example.animequiz.exceptions.ExternalServiceException;
import com.example.animequiz.exceptions.InvalidInputException;
import com.example.animequiz.exceptions.NotFoundException;
import com.example.animequiz.service.catalog.AnimeCatalogService;
import com.example.animequiz.service.ratelimit.SourceRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class YummyAnimeService implements AnimeListImporter {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final SourceRateLimiter rateLimiter;

    private final String baseUrl;
    private final String appToken;

    private final Map<String, Long> nickCache = new ConcurrentHashMap<>();

    public YummyAnimeService(
            SourceRateLimiter rateLimiter, @Value("${app.yummy.base-url:https://api.yani.tv}") String baseUrl,
            @Value("${app.yummy.app-token:}") String appToken) {
        this.rateLimiter = rateLimiter;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.appToken = appToken;
        if (appToken == null || appToken.isBlank()) {
            log.warn("YummyAnime token не настроен, сервис будет недоступен");
        }
    }

    @Override
    public String sourceName() {
        return "yummy";
    }

    @Override
    public ImportResult importList(String nickname) {
        String clean = nickname == null ? "" : nickname.trim();
        if (clean.isEmpty()) throw new InvalidInputException("Укажите никнейм на YummyAnime");

        long userId = nickCache.computeIfAbsent(clean.toLowerCase(), k -> resolveUserId(clean));

        JsonNode root = getJson("/users/" + userId + "/lists");
        JsonNode arr = root.path("response");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new NotFoundException(
                    "Список \"" + clean + "\" пуст, скрыт или пользователь не найден");
        }

        List<RatingEntry> entries = new ArrayList<>(arr.size());
        for (JsonNode item : arr) {
            long animeId = item.path("anime_id").asLong(0);
            if (animeId <= 0) continue;

            JsonNode remote = item.path("remote_ids");
            Long shiki = longOrNull(remote, "shikimori_id");
            Long mal = longOrNull(remote, "myanimelist_id");

            JsonNode userListNode = item.path("user").path("list").path("list");
            int listId = userListNode.path("id").asInt(-1);
            Integer rating = item.path("user").hasNonNull("rating")
                    ? item.path("user").get("rating").asInt() : null;

            entries.add(new RatingEntry(
                    animeId,
                    shiki,
                    mal,
                    animeId,
                    String.valueOf(animeId),
                    mapStatus(listId),
                    rating,
                    null,
                    null
            ));
        }
        log.info("YummyAnime: {} → {} записей", clean, entries.size());
        return new ImportResult(userId, clean, entries.size(), entries);
    }

    @Override
    public AnimeCatalogService.AnimePatch fetchFull(long yummyId) {
        JsonNode root = getJson("/anime/" + yummyId);
        JsonNode anime = root.path("response");
        if (anime.isMissingNode() || anime.isNull()) {
            throw new NotFoundException("YummyAnime не знает " + yummyId);
        }
        return toPatch(anime);
    }

    public List<AnimeCatalogService.AnimePatch> fetchByShikimoriIds(List<Long> ids) {
        return fetchByIds("shikimori_ids", ids);
    }

    public List<AnimeCatalogService.AnimePatch> fetchByMalIds(List<Long> ids) {
        return fetchByIds("mal_ids", ids);
    }

    private List<AnimeCatalogService.AnimePatch> fetchByIds(String param, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String joined = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        JsonNode root = getJson("/anime?" + param + "=" + joined + "&limit=100");
        JsonNode arr = root.path("response");
        if (!arr.isArray()) return List.of();

        List<AnimeCatalogService.AnimePatch> result = new ArrayList<>(arr.size());
        for (JsonNode anime : arr) result.add(toPatch(anime));
        return result;
    }

    private AnimeCatalogService.AnimePatch toPatch(JsonNode anime) {
        long yummyId = anime.path("anime_id").asLong();
        JsonNode remote = anime.path("remote_ids");
        Long shiki = longOrNull(remote, "shikimori_id");
        Long mal = longOrNull(remote, "myanimelist_id");

        String poster = normalizeCdnUrl(anime.path("poster").path("fullsize").asText(null));
        String title = anime.path("title").asText(null);
        String original = anime.path("original").asText(null);

        String russian = hasCyrillic(title) ? title : null;
        String name = original != null ? original : (russian == null ? title : null);
        String titleField = firstNonBlank(russian, name, title, "Unknown");

        return AnimeCatalogService.AnimePatch.builder()
                .yummyId(yummyId)
                .shikimoriId(shiki)
                .malId(mal)
                .name(name)
                .russian(russian)
                .title(titleField)
                .kind(normalizeKind(anime.path("type").path("alias").asText(null)))
                .airingStatus(normalizeAiring(anime.path("anime_status").path("alias").asText(null)))
                .totalEpisodes(anime.path("episodes").path("count").asInt(0))
                .url("https://yummyani.me/catalog/item/"
                        + anime.path("anime_url").asText() + "-" + yummyId)
                .posterUrl(poster)
                .completeness("full")
                .build();
    }

    private static String normalizeCdnUrl(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.startsWith("//")) return "https:" + url;
        return url;
    }

    private long resolveUserId(String nickname) {
        JsonNode root = getJson("/users/" + enc(nickname));
        JsonNode resp = root.path("response");
        if (!resp.hasNonNull("id")) {
            throw new NotFoundException("Пользователь \"" + nickname + "\" не найден на YummyAnime");
        }
        return resp.get("id").asLong();
    }

    private static Long longOrNull(JsonNode node, String field) {
        if (!node.hasNonNull(field)) return null;
        long v = node.get(field).asLong(0);
        return v <= 0 ? null : v;
    }

    private static boolean hasCyrillic(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeBlock.of(s.charAt(i)) == Character.UnicodeBlock.CYRILLIC) return true;
        }
        return false;
    }

    private static String firstNonBlank(String... s) {
        for (String v : s) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String normalizeAiring(String alias) {
        if (alias == null) return null;
        if (alias.equals("announcement")) {
            return "anons";
        } else {
            return alias;
        }
    }

    private static String normalizeKind(String alias) {
        if (alias == null || alias.isBlank()) return null;
        String lower = alias.toLowerCase();
        if (lower.equals("unknown") || lower.equals("undefined")
                || lower.equals("null") || lower.equals("-")) {
            return null;
        }
        return lower;
    }

    private static String mapStatus(int listId) {
        return switch (listId) {
            case 0 -> "watching";
            case 1 -> "planned";
            case 2 -> "completed";
            case 3 -> "dropped";
            case 5 -> "on_hold";
            default -> null;
        };
    }

    private JsonNode getJson(String path) {
        if (appToken == null || appToken.isBlank()) {
            throw new IllegalStateException("YummyAnime token не настроен");
        }
        rateLimiter.acquire("yummy");
        return doGetJson(path);
    }

    private JsonNode doGetJson(String path) {
        String url = baseUrl + path;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(50))
                    .header("X-Application", appToken)
                    .header("Accept", "application/json")
                    .header("Lang", "ru")
                    .GET().build();
            HttpResponse<String> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 404) {
                throw new NotFoundException("YummyAnime 404");
            }
            if (resp.statusCode() != 200) {
                log.error("YummyAnime вернул ошибку. Статус: {}. URL: {}. Ответ: {}",
                        resp.statusCode(), url, resp.body());
                throw new ExternalServiceException("YummyAnime вернул ошибку");
            }
            return mapper.readTree(resp.body());
        } catch (IOException e) {
            log.error("YummyAnime IOException для URL: {}. Сообщение: {}. Причина: {}",
                    url, e.getMessage(), e.getCause() != null ? e.getCause().getMessage() : "нет", e);
            throw new ExternalServiceException("YummyAnime недоступен", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException("Импорт прерван", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}