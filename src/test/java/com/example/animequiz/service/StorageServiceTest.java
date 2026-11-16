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

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    private static final byte[] GIF = {'G', 'I', 'F', '8', '9', 'a', 1, 2, 3};

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

        MockMultipartFile text = new MockMultipartFile("avatar", "a.txt", "text/plain", new byte[] {1, 2, 3});
        assertThatThrownBy(() -> storageService.saveAvatar("u_alice", text))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("PNG");
    }

    @Test
    void saveAvatarStoresFileAndFindAvatarReturnsIt() throws IOException {
        MockMultipartFile file = new MockMultipartFile("avatar", "avatar.png", "image/png", PNG);

        storageService.saveAvatar("u_alice", file);

        Path stored = storageService.findAvatar("u_alice").orElseThrow();
        assertThat(stored.getFileName().toString()).isEqualTo("u_alice.png");
        assertThat(Files.readAllBytes(stored)).isEqualTo(PNG);
    }

    @Test
    void saveAvatarReplacesPreviousFileWithAnotherExtension() throws IOException {
        storageService.saveAvatar("u_alice", new MockMultipartFile("avatar", "a.png", "image/png", PNG));
        storageService.saveAvatar("u_alice", new MockMultipartFile("avatar", "a.jpg", "image/jpeg", JPEG));

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
    void saveAvatarAcceptsGifAndUsesDetectedExtension() throws IOException {
        MockMultipartFile file = new MockMultipartFile("avatar", "a.png", "image/png", GIF);

        storageService.saveAvatar("u_alice", file);

        Path stored = storageService.findAvatar("u_alice").orElseThrow();
        assertThat(stored.getFileName().toString()).isEqualTo("u_alice.gif");
        assertThat(Files.readAllBytes(stored)).isEqualTo(GIF);
    }

    @Test
    void deleteAvatarRemovesAllFilesWithTheSameKey() throws IOException {
        storageService.saveAvatar("u_alice", new MockMultipartFile("avatar", "a.png", "image/png", PNG));

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
