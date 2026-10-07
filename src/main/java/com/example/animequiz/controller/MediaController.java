package com.example.animequiz.controller;

import com.example.animequiz.service.PosterStorageService;
import com.example.animequiz.service.StorageService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Controller
public class MediaController {

    private final PosterStorageService posterStorage;

    public MediaController(PosterStorageService posterStorage) {
        this.posterStorage = posterStorage;
    }

    @GetMapping("/media/posters/{hash}.{ext}")
    @ResponseBody
    public ResponseEntity<Resource> poster(@PathVariable String hash,
                                           @PathVariable String ext) {
        Path p = posterStorage.resolve(hash, ext);
        if (!Files.isRegularFile(p)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(StorageService.mediaTypeFor(p))
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .body(new FileSystemResource(p));
    }
}
