package com.example.animequiz.service;

import com.example.animequiz.exceptions.InvalidInputException;
import com.google.common.util.concurrent.Striped;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class StorageService {

    private static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024;

    private static final int LOCK_STRIPES = 64;

    private final Path root;
    private final Path avatarsDir;

    private final Striped<Lock> locks = Striped.lock(LOCK_STRIPES);

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

    private Lock lockFor(String key) {
        return locks.get(key);
    }

    public static String sanitizeKey(String key) {
        if (key == null) throw new IllegalArgumentException("Пустой ключ хранилища");
        String cleaned = key.replaceAll("[^A-Za-z0-9_-]", "_");
        if (cleaned.isBlank()) throw new IllegalArgumentException("Пустой ключ хранилища");
        return cleaned;
    }

    public void saveAvatar(String key, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new InvalidInputException("Файл не выбран");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new InvalidInputException("Файл слишком большой (макс. 5 МБ)");
        }
        byte[] bytes = file.getBytes();
        String mime = MimeTypes.detectMime(bytes);

        if (!MimeTypes.isAllowedMime(mime)) {
            throw new InvalidInputException("Недопустимый формат: разрешены PNG, JPEG, GIF, WEBP");
        }

        String ext = MimeTypes.extFor(mime);

        String safe = sanitizeKey(key);
        Lock lock = lockFor("avatar:" + safe);
        lock.lock();
        try {
            deleteAvatar(safe);
            Path target = avatarsDir.resolve(safe + "." + ext);
            file.transferTo(target);
        } finally {
            lock.unlock();
        }
    }

    public Optional<Path> findAvatar(String key) {
        String safe = sanitizeKey(key);
        Lock lock = lockFor("avatar:" + safe);
        lock.lock();
        try {
            if (!Files.isDirectory(avatarsDir)) return Optional.empty();
            try (Stream<Path> files = Files.list(avatarsDir)) {
                return files.filter(p -> p.getFileName().toString().startsWith(safe + "."))
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
            files.filter(p -> p.getFileName().toString().startsWith(safe + ".")).forEach(p -> {
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
