package com.example.animequiz.controller;

import com.example.animequiz.service.ShikimoriService;
import com.example.animequiz.service.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

@Controller
public class adminController {

    ShikimoriService shikimoriService;
    StorageService storageService;
    ObjectMapper objectMapper;

    @Autowired
    public adminController(ShikimoriService shikimoriService,
                           StorageService storageService,  ObjectMapper objectMapper) {
        this.shikimoriService = shikimoriService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin")
    public String adminPage() {
        return "admin";
    }

    @ResponseBody
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/ImportSiteAnimeList")
    public Map<String, String> ImportSiteAnimeList() {
        byte[] json;
        try {
            json = shikimoriService.importSiteAnimeList();
            storageService.saveSiteAnimeList(json);
        } catch (Exception e) {
            return Map.of("message", e.getMessage());
        }
        return  Map.of("message", "Импорт прошёл успешно");
    }

    @ResponseBody
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/getUploadDate")
    public Map<String, String> getUploadDate() {
        Optional<byte[]> titles;
        try {
            titles = storageService.readSiteAnimeList();
        } catch (Exception e) {
            return Map.of("message", "Список тайтлов не найден");
        }
        if (titles.isPresent()) {
            try {
                JsonNode jsonNode = objectMapper.readTree(titles.get());
                Instant instant = Instant.parse(jsonNode.get("importedAt").asText());
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-dd-MM")
                        .withZone(ZoneId.of("UTC"));
                return Map.of("message", formatter.format(instant));

            } catch (Exception e) {
                return Map.of("message", "Ошибка при чтении или парсинге JSON: " + e.getMessage());
            }
        }
        return Map.of("message", "Не выполнялся");
    }
}
