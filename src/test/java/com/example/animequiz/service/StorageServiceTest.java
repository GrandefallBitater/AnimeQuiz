package com.example.animequiz.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageServiceTest {

    @TempDir
    Path tempDir;

    private StorageService storage;
    private Path root;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("storage");
        storage = new StorageService(root.toString());
        storage.init(); // ручная замена @PostConstruct
    }

    @Test
    void initCreatesAllDirectories() {
        assertThat(Files.isDirectory(root.resolve("avatars"))).isTrue();
        assertThat(Files.isDirectory(root.resolve("titles"))).isTrue();
        assertThat(Files.isDirectory(root.resolve("site_titles"))).isTrue();
    }

    @Test
    void sanitizeKeyRejectsNull() {
        assertThatThrownBy(() -> StorageService.sanitizeKey(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Пустой ключ");
    }

    @Test
    void sanitizeKeyRejectsEmptyString() {
        assertThatThrownBy(() -> StorageService.sanitizeKey(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sanitizeKeyReplacesForbiddenCharacters() {
        assertThat(StorageService.sanitizeKey("user name@doe!")).isEqualTo("user_name_doe_");
        assertThat(StorageService.sanitizeKey("abc-123_XYZ")).isEqualTo("abc-123_XYZ");
        assertThat(StorageService.sanitizeKey("привет")).isEqualTo("______"); // кириллица -> "_"
    }

    @Test
    void saveAvatarStoresFileAndReturnsFileName() {
        MockMultipartFile file = new MockMultipartFile("f", "a.png", "image/png", new byte[]{1, 2, 3});

        String saved = storage.saveAvatar("user1", file);

        assertThat(saved).isEqualTo("user1.png");
        assertThat(Files.exists(root.resolve("avatars/user1.png"))).isTrue();
    }

    @Test
    void saveAvatarSanitizesKey() {
        MockMultipartFile file = new MockMultipartFile("f", "a.png", "image/png", new byte[]{1});

        String saved = storage.saveAvatar("bad key!", file);

        assertThat(saved).isEqualTo("bad_key_.png");
    }

    @Test
    void saveAvatarMapsContentTypeToExtension() {
        assertThat(storage.saveAvatar("u1",
                new MockMultipartFile("f", "a.jpeg", "image/jpeg", new byte[]{1}))).isEqualTo("u1.jpg");
        assertThat(storage.saveAvatar("u2",
                new MockMultipartFile("f", "a.webp", "image/webp", new byte[]{1}))).isEqualTo("u2.webp");
        assertThat(storage.saveAvatar("u3",
                new MockMultipartFile("f", "a.gif", "image/gif", new byte[]{1}))).isEqualTo("u3.gif");
    }

    @Test
    void saveAvatarRejectsNullFile() {
        assertThatThrownBy(() -> storage.saveAvatar("u", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Файл не выбран");
    }

    @Test
    void saveAvatarRejectsEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("f", "a.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> storage.saveAvatar("u", empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Файл не выбран");
    }

    @Test
    void saveAvatarRejectsTooLargeFile() {
        byte[] tooBig = new byte[5 * 1024 * 1024 + 1]; // 5 МБ + 1 байт
        MockMultipartFile file = new MockMultipartFile("f", "a.png", "image/png", tooBig);

        assertThatThrownBy(() -> storage.saveAvatar("u", file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 МБ");
    }

    @Test
    void saveAvatarRejectsUnsupportedContentType() {
        MockMultipartFile file = new MockMultipartFile("f", "a.txt", "text/plain", new byte[]{1});

        assertThatThrownBy(() -> storage.saveAvatar("u", file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Недопустимый формат");
    }

    @Test
    void saveAvatarReplacesPreviousFileWithSameKey() {
        storage.saveAvatar("user1", new MockMultipartFile("f", "a.png", "image/png", new byte[]{1}));
        storage.saveAvatar("user1", new MockMultipartFile("f", "a.jpg", "image/jpeg", new byte[]{2}));

        assertThat(Files.exists(root.resolve("avatars/user1.png"))).isFalse();
        assertThat(Files.exists(root.resolve("avatars/user1.jpg"))).isTrue();
    }

    @Test
    void findAvatarReturnsStoredFile() {
        storage.saveAvatar("user1", new MockMultipartFile("f", "a.gif", "image/gif", new byte[]{1}));

        assertThat(storage.findAvatar("user1"))
                .isPresent()
                .hasValueSatisfying(p -> assertThat(p.getFileName().toString()).isEqualTo("user1.gif"));
    }

    @Test
    void findAvatarReturnsEmptyForUnknownKey() {
        assertThat(storage.findAvatar("ghost")).isEmpty();
    }

    @Test
    void deleteAvatarRemovesFile() throws IOException {
        storage.saveAvatar("user1", new MockMultipartFile("f", "a.png", "image/png", new byte[]{1}));

        storage.deleteAvatar("user1");

        assertThat(storage.findAvatar("user1")).isEmpty();
    }

    @Test
    void mediaTypeForDetectsExtension() {
        assertThat(StorageService.mediaTypeFor(Path.of("x.png"))).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(StorageService.mediaTypeFor(Path.of("x.gif"))).isEqualTo(MediaType.IMAGE_GIF);
        assertThat(StorageService.mediaTypeFor(Path.of("x.webp")))
                .isEqualTo(MediaType.parseMediaType("image/webp"));
        assertThat(StorageService.mediaTypeFor(Path.of("x.jpg"))).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(StorageService.mediaTypeFor(Path.of("x.unknown"))).isEqualTo(MediaType.IMAGE_JPEG);
    }

    @Test
    void saveAndReadTitlesRoundTrip() {
        byte[] json = "{\"titles\":[]}".getBytes(StandardCharsets.UTF_8);

        storage.saveTitles("user one", json);

        assertThat(Files.exists(root.resolve("titles/u_user_one.json"))).isTrue();
        assertThat(storage.readTitles("user one")).contains(json);
    }

    @Test
    void saveTitlesOverwritesPreviousContent() {
        storage.saveTitles("u1", "old".getBytes(StandardCharsets.UTF_8));
        storage.saveTitles("u1", "new".getBytes(StandardCharsets.UTF_8));

        assertThat(storage.readTitles("u1"))
                .contains("new".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readTitlesReturnsEmptyWhenNoFile() {
        assertThat(storage.readTitles("nobody")).isEmpty();
    }

    @Test
    void saveAndReadSiteAnimeListRoundTripLeavesNoTempFile() {
        byte[] json = "{\"titles\":[]}".getBytes(StandardCharsets.UTF_8);

        storage.saveSiteAnimeList(json);

        assertThat(storage.readSiteAnimeList()).contains(json);
        assertThat(Files.exists(root.resolve("site_titles/Titles.json.tmp"))).isFalse();
    }

    @Test
    void readSiteAnimeListReturnsEmptyWhenNoFile() {
        assertThat(storage.readSiteAnimeList()).isEmpty();
    }
}