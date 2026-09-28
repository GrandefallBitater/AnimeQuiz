package com.example.animequiz.controller;

import com.example.animequiz.Interfaces.CustomUserDetails;
import com.example.animequiz.entity.User;
import com.example.animequiz.repository.UserRepository;
import com.example.animequiz.securityConfig.CustomUserDetailsImpl;
import com.example.animequiz.service.ShikimoriService;
import com.example.animequiz.service.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

@Controller
public class profileController {

    private static final String GUEST_COOKIE = "guestId";
    private static final String NAME_COOKIE = "CustomUserName";

    private final UserRepository userRepository;
    private final StorageService storageService;
    private final ShikimoriService shikimoriService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public profileController(UserRepository userRepository,
                             StorageService storageService,
                             ShikimoriService shikimoriService) {
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.shikimoriService = shikimoriService;
    }

    //TODO make cleanName check for right username
    @PostMapping("/setCustomName")
    @ResponseBody
    public String setCustomUserName(@RequestParam String userName, Authentication authentication, HttpServletResponse response) {
        if ((authentication != null) && authentication.isAuthenticated()) {
            return "вы залогинены, перед установкой никнейма, пожалуйста выйдите из аккаунта";
        }else {
            String cleanName = userName.trim();
            if (!cleanName.isEmpty()) {
                Cookie cookie = new Cookie("CustomUserName", cleanName);
                cookie.setMaxAge(7 * 24 * 60 * 60);
                cookie.setPath("/");
                cookie.setHttpOnly(true);
                response.addCookie(cookie);
            }
            return "operation successfully";
        }
    }

    @GetMapping("/profile")
    public String profile(Model model, Authentication auth,
                          HttpServletRequest request, HttpServletResponse response) {
        String key = storageKey(auth, request, response);
        model.addAttribute("displayName", displayName(auth, request));
        model.addAttribute("authenticated", isAuthed(auth));
        model.addAttribute("hasAvatar", storageService.findAvatar(key).isPresent());

        if (isAuthed(auth)) {
            storageService.readTitles(auth.getName()).ifPresent(bytes -> {
                try {
                    JsonNode info = mapper.readTree(bytes);
                    model.addAttribute("titlesInfo", info);
                } catch (Exception ignored) {
                }
            });
        }
        return "profile";
    }

    @PostMapping("/profile/name")
    public String setName(@RequestParam String userName, Authentication auth,
                          HttpServletResponse response, RedirectAttributes ra) {
        String clean = userName == null ? "" : userName.trim();
        if (clean.isEmpty()) {
            ra.addFlashAttribute("error", "Имя не может быть пустым");
            return "redirect:/profile";
        }
        if (isAuthed(auth)) {
            Optional<User> found = userRepository.findByUsername(auth.getName());
            if (found.isPresent()) {
                User user = found.get();
                user.setDisplayName(clean);
                userRepository.save(user);

                UsernamePasswordAuthenticationToken newAuth = getNewAuth(auth, clean);
                SecurityContextHolder.getContext().setAuthentication(newAuth);
            }
        } else {
            Cookie cookie = new Cookie(NAME_COOKIE, clean);
            cookie.setMaxAge(7 * 24 * 60 * 60);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            response.addCookie(cookie);
        }
        ra.addFlashAttribute("message", "Имя обновлено");
        return "redirect:/profile";
    }

    //TODO create static object with this method
    private static UsernamePasswordAuthenticationToken getNewAuth(Authentication auth, String clean) {
        CustomUserDetailsImpl currentDetails = (CustomUserDetailsImpl) auth.getPrincipal();
        CustomUserDetails updatedDetails = new CustomUserDetailsImpl(
                        currentDetails.getUsername(),
                        currentDetails.getPassword(),
                        clean,
                        currentDetails.getAuthorities(),
                        currentDetails.isEnabled()
                );
        return new UsernamePasswordAuthenticationToken(
                updatedDetails,
                auth.getCredentials(),
                auth.getAuthorities()
        );
    }

    @PostMapping("/profile/avatar")
    public String setAvatar(@RequestParam("avatar") MultipartFile avatar, Authentication auth,
                            HttpServletRequest request, HttpServletResponse response, RedirectAttributes ra) {
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
    public ResponseEntity<Resource> getAvatar(@RequestParam(value = "user", required = false) String targetUser,
                                              Authentication auth,
                                              HttpServletRequest request, HttpServletResponse response) {
        String key;
        if (targetUser != null && !targetUser.isBlank() && isCustomAdmin(auth)) {
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

    //TODO create static object with this method
    private static boolean isCustomAdmin(Authentication a) {
        return a != null && a.getAuthorities().stream()
                .anyMatch(auth -> "ROLE_ADMIN".equals(auth.getAuthority()));
    }

    @PostMapping("/profile/import")
    public String importTitles(@RequestParam String nickname, Authentication auth, RedirectAttributes ra) {
        try {
            ShikimoriService.ImportResult result = shikimoriService.importAnimeList(nickname);
            storageService.saveTitles(auth.getName(), result.json().getBytes(StandardCharsets.UTF_8));
            ra.addFlashAttribute("message",
                    "Импортировано тайтлов: " + result.count() + " (Shikimori: " + result.nickname() + ")");
        } catch (ShikimoriService.ShikimoriException | IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/profile";
    }

    //TODO create static object with this method
    private static boolean isAuthed(Authentication a) {
        return a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken);
    }

    private String storageKey(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        if (isAuthed(auth)) {
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
        if (isAuthed(auth)) {
            return userRepository.findByUsername(auth.getName())
                    .map(u -> (u.getDisplayName() != null && !u.getDisplayName().isBlank())
                            ? u.getDisplayName() : u.getUsername())
                    .orElse(auth.getName());
        }
        String custom = readCookie(request, NAME_COOKIE);
        return (custom != null && !custom.isBlank()) ? custom : "guest";
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

    //TODO create import anime list from YammyAnime
    //TODO create import anime list from MAL
}
