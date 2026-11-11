package com.example.animequiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.animequiz.service.ratelimit.SourceRateLimiter;
import com.example.animequiz.support.LocalHttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PosterStorageServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 1, 2, 3};

    @TempDir
    private Path tempDir;

    private LocalHttpServer http;

    private PosterStorageService posterStorage;

    @BeforeEach
    void setUp() throws IOException {
        http = new LocalHttpServer();
        posterStorage = new PosterStorageService(tempDir.toString(), "test-agent", noRateLimit());
        posterStorage.init();
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    @Test
    void downloadsAndCachesPosterByUrl() throws IOException {
        http.bytes("/poster.png", "image/png", PNG);
        String url = http.baseUrl() + "/poster.png";

        PosterStorageService.StoredPoster stored = posterStorage.downloadAndStore(url);

        assertThat(stored.hash()).hasSize(64);
        assertThat(stored.mime()).isEqualTo("image/png");
        assertThat(stored.ext()).isEqualTo("png");
        assertThat(stored.size()).isEqualTo(PNG.length);
        assertThat(stored.path()).startsWith("posters/");
        assertThat(Files.isRegularFile(posterStorage.resolve(stored.hash(), stored.ext())))
                .isTrue();
        assertThat(Files.isRegularFile(tempDir.resolve("posters").resolve("_url_index.log")))
                .isTrue();

        PosterStorageService.StoredPoster cached = posterStorage.downloadAndStore(url);

        assertThat(cached.hash()).isEqualTo(stored.hash());
        assertThat(http.hits("/poster.png")).isEqualTo(1);
        assertThat(posterStorage.findByUrl(url)).isPresent();
    }

    @Test
    void reloadsIndexFromDiskAfterRestart() throws IOException {
        http.bytes("/poster.png", "image/png", PNG);
        String url = http.baseUrl() + "/poster.png";
        PosterStorageService.StoredPoster stored = posterStorage.downloadAndStore(url);

        PosterStorageService restarted = new PosterStorageService(tempDir.toString(), "test-agent", noRateLimit());
        restarted.init();

        assertThat(restarted.findByUrl(url).orElseThrow().hash()).isEqualTo(stored.hash());
        assertThat(http.hits("/poster.png")).isEqualTo(1);
    }

    @Test
    void migratesLegacyJsonIndexIntoTheLog() throws IOException {
        String hash = "a".repeat(64);
        String url = "https://example.test/legacy.png";
        Path posters = tempDir.resolve("posters");
        Path posterFile = posters.resolve("aa").resolve("aa").resolve(hash + ".png");
        Files.createDirectories(posterFile.getParent());
        Files.write(posterFile, PNG);
        Files.writeString(posters.resolve("_url_index.json"), "{\"" + url + "\":\"" + hash + "\"}");

        PosterStorageService migrated = new PosterStorageService(tempDir.toString(), "test-agent", noRateLimit());
        migrated.init();

        assertThat(migrated.findByUrl(url).orElseThrow().hash()).isEqualTo(hash);
        assertThat(Files.readAllLines(posters.resolve("_url_index.log"))).hasSize(1);
    }

    @Test
    void compactionDropsEntriesWhoseFilesAreGone() throws IOException {
        http.bytes("/a.png", "image/png", PNG);
        http.bytes("/b.png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 9, 9, 9});
        PosterStorageService.StoredPoster a = posterStorage.downloadAndStore(http.baseUrl() + "/a.png");
        posterStorage.downloadAndStore(http.baseUrl() + "/b.png");

        Files.delete(posterStorage.resolve(a.hash(), a.ext()));
        assertThat(posterStorage.findByUrl(http.baseUrl() + "/a.png")).isEmpty();

        posterStorage.compact();

        assertThat(Files.readAllLines(tempDir.resolve("posters").resolve("_url_index.log")))
                .hasSize(1);
    }

    @Test
    void findByUrlReturnsEmptyForUnknownBlankAndStaleEntries() {
        assertThat(posterStorage.findByUrl(null)).isEmpty();
        assertThat(posterStorage.findByUrl("  ")).isEmpty();
        assertThat(posterStorage.findByUrl("http://example.test/unknown.png")).isEmpty();
    }

    @Test
    void rejectsBlankPlaceholderAndNonHttpUrls() {
        assertThatThrownBy(() -> posterStorage.downloadAndStore(" ")).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> posterStorage.downloadAndStore("http://example.test/missing_preview.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("заглушкой");

        assertThatThrownBy(() -> posterStorage.downloadAndStore("ftp://example.test/poster.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http/https");
    }

    @Test
    void rejectsFailedHttpResponses() {
        http.stub("/missing.png", 404, null, new byte[0]);

        assertThatThrownBy(() -> posterStorage.downloadAndStore(http.baseUrl() + "/missing.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("404");
    }

    @Test
    void rejectsResponsesThatAreNotImages() {
        http.bytes("/text", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> posterStorage.downloadAndStore(http.baseUrl() + "/text"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIME");
    }

    @Test
    void resolveBuildsShardedPathAndRejectsInvalidValues() {
        Path resolved = posterStorage.resolve("abcdef123456", "png");

        assertThat(resolved.getFileName().toString()).isEqualTo("abcdef123456.png");
        assertThat(resolved.getParent().getFileName().toString()).isEqualTo("cd");
        assertThat(resolved.getParent().getParent().getFileName().toString()).isEqualTo("ab");

        assertThatThrownBy(() -> posterStorage.resolve(null, "png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> posterStorage.resolve("abc", "png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> posterStorage.resolve("zzzz", "png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> posterStorage.resolve("abcdef", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> posterStorage.resolve("abcdef", "toolong"))
                .isInstanceOf(IllegalArgumentException.class);
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
