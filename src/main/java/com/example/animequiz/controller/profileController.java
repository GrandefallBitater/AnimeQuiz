package com.example.animequiz.controller;

import com.example.animequiz.entity.User;
import com.example.animequiz.exceptions.InvalidInputException;
import com.example.animequiz.repository.UserRepository;
import com.example.animequiz.service.ActiveUserImports;
import com.example.animequiz.service.AuthService;
import com.example.animequiz.service.StorageService;
import com.example.animequiz.service.UserListService;
import com.example.animequiz.service.enrichment.EnrichmentWorker;
import com.example.animequiz.service.importer.AnimeListImporter;
import com.example.animequiz.validation.InputValidation;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Slf4j
@Controller
public class profileController {

    private static final String GUEST_COOKIE = "guestId";
    private static final String NAME_COOKIE = "CustomUserName";

    private final UserRepository userRepository;
    private final StorageService storageService;
    private final UserListService userListService;
    private final EnrichmentWorker enrichmentWorker;
    private final AuthService authService;
    private final Map<String, AnimeListImporter> importers;
    private final ActiveUserImports activeImports;

    @Autowired
    public profileController(
            UserRepository userRepository,
            StorageService storageService,
            UserListService userListService,
            EnrichmentWorker enrichmentWorker,
            AuthService authService,
            List<AnimeListImporter> importersList,
            ActiveUserImports activeImports) {
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.userListService = userListService;
        this.enrichmentWorker = enrichmentWorker;
        this.authService = authService;
        this.importers = importersList.stream()
                .collect(Collectors.toMap(i -> i.sourceName().toLowerCase(), i -> i));
        this.activeImports = activeImports;
    }

    @PostMapping("/setCustomName")
    @ResponseBody
    public ResponseEntity<Map<String, String>> setCustomUserName(
            @RequestParam String userName,
            Authentication authentication,
            HttpServletResponse response,
            HttpServletRequest request) {
        String result = executeNameUpdate(userName, authentication, response, request);

        if ("success".equals(result)) {
            return ResponseEntity.ok(Collections.singletonMap("message", "Имя успешно обновлено"));
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Collections.singletonMap("message", result));
        }
    }

    @GetMapping("/profile")
    public String profile(Model model, Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        String key = storageKey(auth, request, response);
        model.addAttribute("displayName", displayName(auth, request));
        model.addAttribute("authenticated", AuthService.isAuthenticated(auth));
        model.addAttribute("hasAvatar", storageService.findAvatar(key).isPresent());
        if (AuthService.isAuthenticated(auth)) {
            userRepository.findByUsername(auth.getName()).ifPresent(u -> {
                boolean isAdmin = u.getAuthorities().stream().anyMatch(a -> {
                    String val = a.getAuthority();
                    return "ADMIN".equalsIgnoreCase(val) || "ROLE_ADMIN".equalsIgnoreCase(val);
                });
                model.addAttribute("userRole", isAdmin ? "ADMIN" : "USER");
            });
        }
        return "profile";
    }

    @PostMapping("/profile/import/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> importTitlesJson(
            @RequestParam String nickname,
            @RequestParam(defaultValue = "shikimori") String source,
            Authentication auth) {

        if (!AuthService.isAuthenticated(auth)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Требуется вход в аккаунт"));
        }

        String username = auth.getName();
        activeImports.start(username);
        try {
            AnimeListImporter importer = importerFor(source);
            AnimeListImporter.ImportResult result = importer.importList(nickname, () -> activeImports.touch(username));

            userListService.replaceUserList(username, importer.sourceName(), result);
            enrichmentWorker.trigger();

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put(
                    "message",
                    "Импортировано: " + result.count() + " (" + importer.sourceName() + ": " + result.nickname() + ")");
            resp.put("count", result.count());
            resp.put("nickname", result.nickname());
            resp.put("source", importer.sourceName());
            return ResponseEntity.ok(resp);

        } finally {
            activeImports.finish(username);
        }
    }

    @GetMapping("/profile/titles")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getTitles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "60") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "true") boolean onlyLocal,
            @RequestParam(defaultValue = "default") String sort,
            Authentication auth) {
        if (!AuthService.isAuthenticated(auth)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("titles", List.of()));
        }

        Set<String> statuses = new LinkedHashSet<>();
        if (status != null && !status.isBlank()) {
            for (String s : status.split(",")) {
                String clean = s.trim().toLowerCase(Locale.ROOT);
                if (!clean.isEmpty()) statuses.add(clean);
            }
        }

        UserListService.UserListPage view =
                userListService.getUserListPage(auth.getName(), page, size, q, statuses, onlyLocal, sort);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("titles", view.titles());
        resp.put("count", view.titles().size());
        resp.put("page", view.page());
        resp.put("size", view.size());
        resp.put("hiddenCount", view.hiddenCount());
        resp.put("totalCount", view.totalCount());
        resp.put("readyCount", view.readyCount());
        resp.put("withPosterCount", view.withPosterCount());
        resp.put("filteredCount", view.filteredCount());
        resp.put("hasMore", view.hasMore());
        resp.put("statusCounts", view.statusCounts());

        userListService.getLastImport(auth.getName()).ifPresent(ui -> {
            resp.put("source", ui.getSource());
            resp.put("importedAt", ui.getImportedAt().toString());
        });
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/profile/import/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> importStatus(Authentication auth) {
        if (!AuthService.isAuthenticated(auth))
            return ResponseEntity.status(401).build();

        boolean running = activeImports.isRunning(auth.getName());
        UserListService.UserListView view = userListService.getUserListView(auth.getName());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("running", running);
        resp.put("total", view.totalCount());
        resp.put("ready", view.titles().size());
        resp.put("hidden", view.hiddenCount());
        resp.put("done", !running && view.hiddenCount() == 0);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/profile/name")
    public String setName(
            @RequestParam String userName,
            Authentication auth,
            HttpServletResponse response,
            HttpServletRequest request,
            RedirectAttributes ra) {
        String result = executeNameUpdate(userName, auth, response, request);

        if ("success".equals(result)) {
            ra.addFlashAttribute("message", "Имя обновлено");
        } else {
            ra.addFlashAttribute("error", result);
        }
        return "redirect:/profile";
    }

    private String executeNameUpdate(
            String userName, Authentication auth, HttpServletResponse response, HttpServletRequest request) {
        final String clean;
        try {
            clean = InputValidation.normalizeDisplayName(userName);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }

        if (AuthService.isAuthenticated(auth)) {
            Optional<User> found = userRepository.findByUsername(auth.getName());
            if (found.isPresent()) {
                User user = found.get();
                user.setDisplayName(clean);
                userRepository.save(user);

                UsernamePasswordAuthenticationToken newAuth = authService.refreshAuthentication(auth, user);
                authService.updateSecurityContext(newAuth, request);
            }
        } else {
            Cookie cookie = new Cookie(NAME_COOKIE, URLEncoder.encode(clean, StandardCharsets.UTF_8));
            cookie.setMaxAge(7 * 24 * 60 * 60);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            response.addCookie(cookie);
        }
        return "success";
    }

    @PostMapping("/profile/avatar")
    public String setAvatar(
            @RequestParam("avatar") MultipartFile avatar,
            Authentication auth,
            HttpServletRequest request,
            HttpServletResponse response,
            RedirectAttributes ra) {
        String key = storageKey(auth, request, response);
        try {
            storageService.saveAvatar(key, avatar);
            ra.addFlashAttribute("message", "Аватар обновлён");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/profile";
    }

    @GetMapping("/profile/avatar")
    @ResponseBody
    public ResponseEntity<Resource> getAvatar(
            @RequestParam(value = "user", required = false) String targetUser,
            Authentication auth,
            HttpServletRequest request,
            HttpServletResponse response) {
        String key;
        if (targetUser != null && !targetUser.isBlank() && AuthService.isAdmin(auth)) {
            key = "u_" + targetUser;
        } else {
            key = storageKey(auth, request, response);
        }

        Optional<Path> avatar = storageService.findAvatar(key);
        if (avatar.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path file = avatar.get();
        return ResponseEntity.ok()
                .contentType(StorageService.mediaTypeFor(file))
                .body(new FileSystemResource(file));
    }

    @PostMapping("/profile/avatar/json")
    @ResponseBody
    public ResponseEntity<Map<String, String>> setAvatarJson(
            @RequestParam("avatar") MultipartFile avatar,
            Authentication auth,
            HttpServletRequest request,
            HttpServletResponse response) {
        String key = storageKey(auth, request, response);
        try {
            storageService.saveAvatar(key, avatar);
            return ResponseEntity.ok(Collections.singletonMap("message", "Аватар обновлён"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Collections.singletonMap("message", e.getMessage()));
        }
    }

    private String storageKey(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        if (AuthService.isAuthenticated(auth)) {
            return "u_" + auth.getName();
        }
        String guestId = readCookie(request, GUEST_COOKIE);
        if (guestId == null || guestId.isBlank()) {
            guestId = UUID.randomUUID().toString();
            Cookie cookie = new Cookie(GUEST_COOKIE, guestId);
            cookie.setMaxAge(365 * 24 * 60 * 60);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            response.addCookie(cookie);
        }
        return "g_" + guestId;
    }

    private String displayName(Authentication auth, HttpServletRequest request) {
        if (AuthService.isAuthenticated(auth)) {
            return userRepository
                    .findByUsername(auth.getName())
                    .map(u -> (u.getDisplayName() != null && !u.getDisplayName().isBlank())
                            ? u.getDisplayName()
                            : u.getUsername())
                    .orElse(auth.getName());
        }
        String custom = readCookie(request, NAME_COOKIE);
        if (custom == null || custom.isBlank()) {
            return "guest";
        }
        try {
            return URLDecoder.decode(custom, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return custom;
        }
    }

    private static String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie c : request.getCookies()) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private AnimeListImporter importerFor(String source) {
        AnimeListImporter importer = importers.get(source == null ? "shikimori" : source.toLowerCase());
        if (importer == null) {
            throw new InvalidInputException("Неизвестный источник: " + source);
        }
        return importer;
    }
}
