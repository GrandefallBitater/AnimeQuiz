package com.example.animequiz.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class StorageService {

    private static final Map<String, String> ALLOWED_IMAGE_TYPES = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/gif", "gif",
            "image/webp", "webp"
    );
    private static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024; // 5 МБ

    private final Path root;
    private final Path avatarsDir;
    private final Path titlesDir;
    private final Path siteTitlesDir;

    public StorageService(@Value("${app.storage.location}") String location) {
        this.root = Paths.get(location).toAbsolutePath().normalize();
        this.avatarsDir = root.resolve("avatars");
        this.titlesDir = root.resolve("titles");
        this.siteTitlesDir = root.resolve("site_titles");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(avatarsDir);
            Files.createDirectories(titlesDir);
            Files.createDirectories(siteTitlesDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось подготовить хранилище: " + root, e);
        }
    }

    public static String sanitizeKey(String key) {
        if (key == null) throw new IllegalArgumentException("Пустой ключ хранилища");
        String cleaned = key.replaceAll("[^A-Za-z0-9_-]", "_");
        if (cleaned.isBlank()) throw new IllegalArgumentException("Пустой ключ хранилища");
        return cleaned;
    }

    public String saveAvatar(String key, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Файл не выбран");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("Файл слишком большой (макс. 5 МБ)");
        }
        String ext = ALLOWED_IMAGE_TYPES.get(file.getContentType());
        if (ext == null) {
            throw new IllegalArgumentException("Недопустимый формат: разрешены PNG, JPEG, GIF, WEBP");
        }
        String safe = sanitizeKey(key);
        try {
            deleteAvatar(safe);
            Path target = avatarsDir.resolve(safe + "." + ext);
            file.transferTo(target);
            return target.getFileName().toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить изображение", e);
        }
    }

    public Optional<Path> findAvatar(String key) {
        String safe = sanitizeKey(key);
        if (!Files.isDirectory(avatarsDir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(avatarsDir)) {
            return files
                    .filter(p -> p.getFileName().toString().startsWith(safe + "."))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void deleteAvatar(String safe) throws IOException {
        if (!Files.isDirectory(avatarsDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(avatarsDir)) {
            files.filter(p -> p.getFileName().toString().startsWith(safe + "."))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException("не удалось удалить аватар", e);
                        }
                    });
        }
    }

    public static MediaType mediaTypeFor(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (name.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (name.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.IMAGE_JPEG;
    }

    public void saveTitles(String username, byte[] json) {
        Path target = titlesDir.resolve("u_" + sanitizeKey(username) + ".json");
        try {
            Files.write(target, json,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить список тайтлов", e);
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    public void saveSiteAnimeList(byte[] json) {
        Path target = siteTitlesDir.resolve("Titles.json");
        Path tempTarget = siteTitlesDir.resolve("Titles.json.tmp");

        try {
            Files.write(tempTarget, json,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            Files.move(tempTarget, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException e) {
            try {
                Files.deleteIfExists(tempTarget);
            } catch (IOException ignored) {
                throw new UncheckedIOException(
                        "Не удалось сохранить список тайтлов, старый файл сохранен, временный файл сохранён", e);
            }
            throw new UncheckedIOException("Не удалось сохранить список тайтлов, старый файл сохранен", e);
        }
    }

    public Optional<byte[]> readTitles(String username) {
        Path source = titlesDir.resolve("u_" + sanitizeKey(username) + ".json");
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(source));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<byte[]> readSiteAnimeList() {
        Path source = siteTitlesDir.resolve("Titles.json");
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(source));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    //TODO when importing siteAnimeList sort by name
    //TODO when importing siteAnimeList we should download all pics
}
