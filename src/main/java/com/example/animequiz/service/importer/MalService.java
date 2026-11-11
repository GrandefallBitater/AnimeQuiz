package com.example.animequiz.service.importer;

import com.example.animequiz.exceptions.InvalidInputException;
import com.example.animequiz.exceptions.NotFoundException;
import com.example.animequiz.service.catalog.AnimeCatalogService;
import com.example.animequiz.service.ratelimit.SourceRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MalService implements AnimeListImporter {

    private static final int PAGE_SIZE = 300;
    private static final int MAX_PAGES = 200;

    private final String webBaseUrl;
    private final String apiBaseUrl;
    private final String clientId;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SourceRateLimiter rateLimiter;

    public MalService(
            @Value("${app.mal.web-base-url:https://myanimelist.net}") String webBaseUrl,
            @Value("${app.mal.base-url:https://api.myanimelist.net/v2}") String apiBaseUrl,
            @Value("${app.mal.client-id:}") String clientId,
            SourceRateLimiter rateLimiter) {
        this.webBaseUrl = webBaseUrl.replaceAll("/+$", "");
        this.apiBaseUrl = apiBaseUrl.replaceAll("/+$", "");
        this.clientId = clientId;
        this.rateLimiter = rateLimiter;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        if (clientId == null || clientId.isBlank()) {
            log.warn("MAL токен не настроен - сервис будет недоступен");
        }
    }

    @Override
    public String sourceName() {
        return "myanimelist";
    }

    @Override
    public ImportResult importList(String nickname) {
        return importList(nickname, () -> {});
    }

    @Override
    public ImportResult importList(String nickname, Runnable onProgress) {
        String clean = nickname == null ? "" : nickname.trim();
        if (clean.isEmpty()) throw new InvalidInputException("Укажите никнейм на MyAnimeList");

        List<RatingEntry> entries = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            onProgress.run();
            JsonNode data = fetchPage(clean, page * PAGE_SIZE);
            if (data == null || !data.isArray() || data.isEmpty()) break;
            for (JsonNode item : data) {
                long malId = item.path("anime_id").asLong(0);
                if (malId <= 0) continue;
                boolean rewatching = item.path("is_rewatching").asBoolean(false);
                entries.add(new RatingEntry(
                        malId,
                        null,
                        malId,
                        null,
                        String.valueOf(malId),
                        mapStatus(item.path("status").asInt(0), rewatching),
                        item.path("score").asInt(0),
                        item.path("num_watched_episodes").asInt(0),
                        rewatching ? 1 : 0));
            }
            if (data.size() < PAGE_SIZE) break;
            sleep(300);
        }
        if (entries.isEmpty()) {
            throw new NotFoundException("Пользователь не найден на MAL");
        }
        log.info("MAL: {} → {} записей", clean, entries.size());
        return new ImportResult(0L, clean, entries.size(), entries);
    }

    private JsonNode fetchPage(String username, int offset) {
        String url = webBaseUrl + "/animelist/" + enc(username) + "/load.json?offset=" + offset + "&status=7";
        try {
            rateLimiter.acquire("mal");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0")
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", webBaseUrl + "/animelist/" + enc(username))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
            if (code == 403 || code == 404) return null;
            if (code != 200) {
                log.warn("MAL web {}: {}", code, url);
                return null;
            }
            return mapper.readTree(resp.body());
        } catch (Exception e) {
            log.warn("Ошибка запроса к MAL: {}", e.toString());
            return null;
        }
    }

    @Override
    public AnimeCatalogService.AnimePatch fetchFull(long malId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("MAL client-id не настроен (app.mal.client-id)");
        }
        String url = apiBaseUrl + "/anime/" + malId
                + "?fields=id,title,main_picture,alternative_titles,media_type,num_episodes,status";
        JsonNode root = getApiJson(url);
        if (root == null) throw new NotFoundException("MAL не знает " + malId);

        String poster = root.path("main_picture").path("large").asText(null);
        if (poster == null || poster.isBlank()) {
            poster = root.path("main_picture").path("medium").asText(null);
        }

        return AnimeCatalogService.AnimePatch.builder()
                .malId(malId)
                .name(root.path("title").asText(null))
                .title(root.path("title").asText(null))
                .kind(mapKind(root.path("media_type").asText(null)))
                .airingStatus(mapAiringStatusFromApi(root.path("status").asText(null)))
                .totalEpisodes(root.path("num_episodes").asInt(0))
                .url("https://myanimelist.net/anime/" + malId)
                .posterUrl(poster)
                .completeness("partial")
                .build();
    }

    private JsonNode getApiJson(String url) {
        try {
            rateLimiter.acquire("mal");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("X-MAL-CLIENT-ID", clientId)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return null;
            return mapper.readTree(resp.body());
        } catch (Exception e) {
            log.warn("MAL API fail: {}", e.toString());
            return null;
        }
    }

    private static String mapStatus(int status, boolean rewatching) {
        if (rewatching) return "watching";
        return switch (status) {
            case 1 -> "watching";
            case 2 -> "completed";
            case 3 -> "on_hold";
            case 4 -> "dropped";
            case 6 -> "planned";
            default -> null;
        };
    }

    private static String mapKind(String malType) {
        if (malType == null) return null;
        return switch (malType) {
            case "TV" -> "tv";
            case "TV Special" -> "tv_special";
            case "Movie" -> "movie";
            case "OVA" -> "ova";
            case "ONA" -> "ona";
            case "Special" -> "special";
            case "Music" -> "music";
            case "PV" -> "pv";
            case "CM" -> "cm";
            default -> malType.toLowerCase();
        };
    }

    private static String mapAiringStatusFromApi(String s) {
        if (s == null) return null;
        return switch (s) {
            case "currently_airing" -> "ongoing";
            case "finished_airing" -> "released";
            case "not_yet_aired" -> "anons";
            default -> null;
        };
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
