package com.example.animequiz.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

@Service
public class StorageService {

    private static final Map<String, String> ALLOWED_IMAGE_TYPES = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/gif", "gif",
            "image/webp", "webp"
    );
    private static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024;
    private final Path root;
    private final Path avatarsDir;

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public StorageService(@Value("${app.storage.location}") String location) {
        this.root = Paths.get(location).toAbsolutePath().normalize();
        this.avatarsDir = root.resolve("avatars");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(avatarsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось подготовить хранилище: " + root, e);
        }
    }

    private ReentrantLock lockFor(String key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    public static String sanitizeKey(String key) {
        if (key == null) throw new IllegalArgumentException("Пустой ключ хранилища");
        String cleaned = key.replaceAll("[^A-Za-z0-9_-]", "_");
        if (cleaned.isBlank()) throw new IllegalArgumentException("Пустой ключ хранилища");
        return cleaned;
    }

    public void saveAvatar(String key, MultipartFile file) {
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
        ReentrantLock lock = lockFor("avatar:" + safe);
        lock.lock();
        try {
            deleteAvatar(safe);
            Path target = avatarsDir.resolve(safe + "." + ext);
            file.transferTo(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить изображение", e);
        } finally {
            lock.unlock();
        }
    }

    public Optional<Path> findAvatar(String key) {
        String safe = sanitizeKey(key);
        ReentrantLock lock = lockFor("avatar:" + safe);
        lock.lock();
        try {
            if (!Files.isDirectory(avatarsDir)) return Optional.empty();
            try (Stream<Path> files = Files.list(avatarsDir)) {
                return files
                        .filter(p -> p.getFileName().toString().startsWith(safe + "."))
                        .findFirst();
            } catch (IOException e) {
                return Optional.empty();
            }
        } finally {
            lock.unlock();
        }
    }

    public void deleteAvatar(String safe) throws IOException {
        if (!Files.isDirectory(avatarsDir)) return;
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
}