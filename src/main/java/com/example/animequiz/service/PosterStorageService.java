package com.example.animequiz.service;

import com.example.animequiz.service.ratelimit.SourceRateLimiter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PosterStorageService {

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_MIME = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private static final List<String> ALL_EXT = List.of("jpg", "png", "gif", "webp");
    private static final long INDEX_MAX_ENTRIES = 100_000;
    private static final long INDEX_MIN_COMPACT_LINES = 1_000;

    private final Path postersDir;
    private final Path indexLogFile;
    private final Path legacyIndexFile;
    private final HttpClient http;
    private final String userAgent;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Cache<String, String> urlToHash =
            CacheBuilder.newBuilder().maximumSize(INDEX_MAX_ENTRIES).build();
    private final ReentrantLock indexWriteLock = new ReentrantLock();
    private final SourceRateLimiter rateLimiter;
    private long indexLinesOnDisk;

    public PosterStorageService(
            @Value("${app.storage.location}") String location,
            @Value("${app.shikimori.user-agent}") String userAgent,
            SourceRateLimiter rateLimiter) {
        this.postersDir = Paths.get(location).toAbsolutePath().normalize().resolve("posters");
        this.rateLimiter = rateLimiter;
        this.indexLogFile = this.postersDir.resolve("_url_index.log");
        this.legacyIndexFile = this.postersDir.resolve("_url_index.json");
        this.userAgent = userAgent;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(postersDir);
        boolean migrated = false;
        if (Files.isRegularFile(indexLogFile)) {
            indexLinesOnDisk = readLog(indexLogFile);
        } else if (Files.isRegularFile(legacyIndexFile)) {
            readLegacyIndex();
            migrated = true;
        }
        if (migrated || indexLinesOnDisk > compactionThreshold()) {
            compact();
        }
    }

    public record StoredPoster(String hash, String path, String mime, long size, String ext) {}

    public Optional<StoredPoster> findByUrl(String url) {
        if (url == null || url.isBlank()) return Optional.empty();

        String hash = urlToHash.getIfPresent(url);
        if (hash == null) return Optional.empty();

        for (String ext : ALL_EXT) {
            Path p = resolve(hash, ext);
            if (Files.isRegularFile(p)) {
                try {
                    String localPath =
                            "posters/" + postersDir.relativize(p).toString().replace('\\', '/');
                    return Optional.of(new StoredPoster(hash, localPath, mimeForExt(ext), Files.size(p), ext));
                } catch (IOException ignored) {
                }
            }
        }
        urlToHash.invalidate(url);
        return Optional.empty();
    }

    public StoredPoster downloadAndStore(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Пустой posterUrl");
        }

        if (url.startsWith("//")) {
            url = "https:" + url;
        }

        if (url.contains("missing_preview")) {
            throw new IllegalArgumentException("URL является заглушкой: " + url);
        }

        Optional<StoredPoster> cached = findByUrl(url);
        if (cached.isPresent()) {
            return cached.get();
        }

        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Разрешены только http/https");
        }

        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", userAgent)
                    .header("Accept", "image/*")
                    .GET()
                    .build();

            rateLimiter.acquire("poster");
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Poster HTTP " + resp.statusCode());
            }

            byte[] bytes = resp.body();
            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                throw new IllegalArgumentException("Некорректный размер постера");
            }

            String mime =
                    detectMime(bytes, resp.headers().firstValue("Content-Type").orElse(null));
            if (!ALLOWED_MIME.contains(mime)) {
                throw new IllegalArgumentException("Недопустимый MIME: " + mime);
            }

            String hash = sha256Hex(bytes);
            String ext = extFor(mime);

            String p1 = hash.substring(0, 2);
            String p2 = hash.substring(2, 4);
            Path relative = Paths.get(p1, p2, hash + "." + ext);
            Path target = postersDir.resolve(relative).normalize();

            if (!target.startsWith(postersDir)) {
                throw new IllegalStateException("Path traversal");
            }

            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                Path tmp = Files.createTempFile(target.getParent(), hash, ".tmp");
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (FileAlreadyExistsException ignored) {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    Files.deleteIfExists(tmp);
                    throw e;
                }
            }

            registerUrl(url, hash);

            String localPath = "posters/" + relative.toString().replace('\\', '/');
            return new StoredPoster(hash, localPath, mime, bytes.length, ext);

        } catch (IOException e) {
            throw new UncheckedIOException(new IOException("Не удалось скачать постер: " + url, e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("Прервано скачивание постера: " + url, e));
        }
    }

    private void registerUrl(String url, String hash) {
        String previous = urlToHash.asMap().put(url, hash);
        if (hash.equals(previous)) return;

        indexWriteLock.lock();
        try {
            Files.writeString(
                    indexLogFile,
                    hash + " " + url + '\n',
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            indexLinesOnDisk++;
            if (indexLinesOnDisk > compactionThreshold()) {
                compactIndex();
            }
        } catch (IOException e) {
            log.warn("Не удалось сохранить URL-индекс постеров", e);
        } finally {
            indexWriteLock.unlock();
        }
    }

    private long compactionThreshold() {
        return Math.max(INDEX_MIN_COMPACT_LINES, 2L * urlToHash.size());
    }

    void compact() {
        indexWriteLock.lock();
        try {
            compactIndex();
        } catch (IOException e) {
            log.warn("Не удалось сжать URL-индекс постеров", e);
        } finally {
            indexWriteLock.unlock();
        }
    }

    private void compactIndex() throws IOException {
        Path tmp = Files.createTempFile(postersDir, "_url_index.", ".tmp");
        long written = 0;
        try (BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : urlToHash.asMap().entrySet()) {
                out.write(entry.getValue());
                out.write(' ');
                out.write(entry.getKey());
                out.write('\n');
                written++;
            }
        }
        try {
            Files.move(tmp, indexLogFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, indexLogFile, StandardCopyOption.REPLACE_EXISTING);
        }
        indexLinesOnDisk = written;
    }

    private long readLog(Path file) {
        long lines = 0;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                lines++;
                if (line.length() < 65 || line.charAt(64) != ' ') continue;
                String hash = line.substring(0, 64);
                String url = line.substring(65);
                if (isSha256(hash) && !url.isBlank()) {
                    urlToHash.put(url, hash);
                }
            }
        } catch (IOException e) {
            log.warn("Не удалось прочитать URL-индекс постеров, стартуем с пустым", e);
        }
        log.info("Загружен URL-индекс постеров: {} записей из {} строк", urlToHash.size(), lines);
        return lines;
    }

    private void readLegacyIndex() {
        try {
            Map<String, String> loaded =
                    mapper.readValue(legacyIndexFile.toFile(), new TypeReference<HashMap<String, String>>() {});
            loaded.forEach((url, hash) -> {
                if (hash != null && isSha256(hash)) urlToHash.put(url, hash);
            });
            log.info("Старый URL-индекс постеров перенесён в журнал: {} записей", loaded.size());
        } catch (IOException e) {
            log.warn("Не удалось прочитать старый URL-индекс постеров", e);
        }
    }

    private static boolean isSha256(String value) {
        if (value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return false;
        }
        return true;
    }

    public Path resolve(String hash, String ext) {
        if (hash == null || hash.length() < 4 || !hash.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException("Некорректный hash постера");
        }
        if (ext == null || !ext.matches("[a-z0-9]{2,4}")) {
            throw new IllegalArgumentException("Некорректное расширение");
        }
        String p1 = hash.substring(0, 2);
        String p2 = hash.substring(2, 4);
        Path p = postersDir.resolve(p1).resolve(p2).resolve(hash + "." + ext).normalize();
        if (!p.startsWith(postersDir)) {
            throw new IllegalStateException("Path traversal");
        }
        return p;
    }

    private static String mimeForExt(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String extFor(String mime) {
        return switch (mime) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }

    private static String detectMime(byte[] b, String header) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return "image/gif";
        }
        if (b.length >= 12
                && b[0] == 'R'
                && b[1] == 'I'
                && b[2] == 'F'
                && b[3] == 'F'
                && b[8] == 'W'
                && b[9] == 'E'
                && b[10] == 'B'
                && b[11] == 'P') {
            return "image/webp";
        }
        if (header != null) {
            int semi = header.indexOf(';');
            String ct = (semi > 0 ? header.substring(0, semi) : header).trim().toLowerCase();
            if (ALLOWED_MIME.contains(ct)) return ct;
        }
        return "application/octet-stream";
    }
}
