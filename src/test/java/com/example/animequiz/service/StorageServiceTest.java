package com.example.animequiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.animequiz.exceptions.InvalidInputException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class StorageServiceTest {

    @TempDir
    private Path tempDir;

    private StorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new StorageService(tempDir.toString());
        storageService.init();
    }

    @Test
    void sanitizeKeyReplacesUnsafeCharacters() {
        assertThat(StorageService.sanitizeKey("a b/c")).isEqualTo("a_b_c");
        assertThat(StorageService.sanitizeKey("u_alice")).isEqualTo("u_alice");
        assertThatThrownBy(() -> StorageService.sanitizeKey(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageService.sanitizeKey("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveAvatarRejectsMissingEmptyOversizedAndUnsupportedFiles() {
        assertThatThrownBy(() -> storageService.saveAvatar("u_alice", null)).isInstanceOf(InvalidInputException.class);

        MockMultipartFile empty = new MockMultipartFile("avatar", "empty.png", "image/png", new byte[0]);
        assertThatThrownBy(() -> storageService.saveAvatar("u_alice", empty)).isInstanceOf(InvalidInputException.class);

        MockMultipartFile tooLarge =
                new MockMultipartFile("avatar", "big.png", "image/png", new byte[5 * 1024 * 1024 + 1]);
        assertThatThrownBy(() -> storageService.saveAvatar("u_alice", tooLarge))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("5 МБ");

        MockMultipartFile gif = new MockMultipartFile("avatar", "a.gif", "image/gif", new byte[] {1, 2, 3});
        assertThatThrownBy(() -> storageService.saveAvatar("u_alice", gif))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("PNG");
    }

    @Test
    void saveAvatarStoresFileAndFindAvatarReturnsIt() throws IOException {
        byte[] content = {1, 2, 3, 4};
        MockMultipartFile file = new MockMultipartFile("avatar", "avatar.png", "image/png", content);

        storageService.saveAvatar("u_alice", file);

        Path stored = storageService.findAvatar("u_alice").orElseThrow();
        assertThat(stored.getFileName().toString()).isEqualTo("u_alice.png");
        assertThat(Files.readAllBytes(stored)).isEqualTo(content);
    }

    @Test
    void saveAvatarReplacesPreviousFileWithAnotherExtension() throws IOException {
        storageService.saveAvatar("u_alice", new MockMultipartFile("avatar", "a.png", "image/png", new byte[] {1}));
        storageService.saveAvatar("u_alice", new MockMultipartFile("avatar", "a.jpg", "image/jpeg", new byte[] {2}));

        assertThat(storageService
                        .findAvatar("u_alice")
                        .orElseThrow()
                        .getFileName()
                        .toString())
                .isEqualTo("u_alice.jpg");
        try (Stream<Path> files = Files.list(tempDir.resolve("avatars"))) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    void deleteAvatarRemovesAllFilesWithTheSameKey() throws IOException {
        storageService.saveAvatar("u_alice", new MockMultipartFile("avatar", "a.png", "image/png", new byte[] {1}));

        storageService.deleteAvatar("u_alice");

        assertThat(storageService.findAvatar("u_alice")).isEmpty();
    }

    @Test
    void findAvatarReturnsEmptyForUnknownKey() {
        assertThat(storageService.findAvatar("u_ghost")).isEmpty();
    }

    @Test
    void deleteAvatarDoesNothingWhenDirectoryDoesNotExist() throws IOException {
        StorageService missing = new StorageService(tempDir.resolve("missing").toString());

        missing.deleteAvatar("u_alice");
    }

    @Test
    void mediaTypeForMapsFileExtensions() {
        assertThat(StorageService.mediaTypeFor(Path.of("poster.png"))).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(StorageService.mediaTypeFor(Path.of("poster.gif"))).isEqualTo(MediaType.IMAGE_GIF);
        assertThat(StorageService.mediaTypeFor(Path.of("poster.webp")))
                .isEqualTo(MediaType.parseMediaType("image/webp"));
        assertThat(StorageService.mediaTypeFor(Path.of("poster.jpg"))).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(StorageService.mediaTypeFor(Path.of("poster.unknown"))).isEqualTo(MediaType.IMAGE_JPEG);
    }
}
