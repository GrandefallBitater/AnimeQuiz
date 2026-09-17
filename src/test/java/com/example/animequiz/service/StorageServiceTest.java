package com.example.animequiz.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StorageServiceIntegrationTest {

    @Autowired
    private StorageService storageService;

    @TempDir
    static Path tempStorageLocation;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.location", () -> tempStorageLocation.toAbsolutePath().toString());
    }

    @AfterEach
    void cleanUp() throws IOException {
        Path avatarsDir = tempStorageLocation.resolve("avatars");
        if (Files.exists(avatarsDir)) {
            try (var stream = Files.list(avatarsDir)) {
                stream.forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
        Path titlesDir = tempStorageLocation.resolve("titles");
        if (Files.exists(titlesDir)) {
            try (var stream = Files.list(titlesDir)) {
                stream.forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    @Test
    void shouldInitializeSaveAndReadCorrectly() {
        Path expectedAvatarsDir = tempStorageLocation.resolve("avatars");
        Path expectedTitlesDir = tempStorageLocation.resolve("titles");

        assertThat(expectedAvatarsDir).isDirectory();
        assertThat(expectedTitlesDir).isDirectory();

        MockMultipartFile file = new MockMultipartFile(
                "file", "integration-test.webp", "image/webp", "fake-webp-bytes".getBytes()
        );

        String savedFileName = storageService.saveAvatar("shikimori-user", file);
        assertThat(savedFileName).isEqualTo("shikimori-user.webp");

        Optional<Path> foundAvatar = storageService.findAvatar("shikimori-user");
        assertThat(foundAvatar).isPresent();
        assertThat(foundAvatar.get()).exists();

        String username = "naruto_kun";
        byte[] rawJson = "{\"status\":\"completed\",\"count\":42}".getBytes(StandardCharsets.UTF_8);

        storageService.saveTitles(username, rawJson);

        Optional<byte[]> savedJsonBytes = storageService.readTitles(username);
        assertThat(savedJsonBytes).isPresent();

        String resultJson = new String(savedJsonBytes.get(), StandardCharsets.UTF_8);
        assertThat(resultJson).contains("completed").contains("42");
    }
}