package com.example.animequiz.controller;

import com.example.animequiz.dto.AdminUpdateRequest;
import com.example.animequiz.dto.SelfUpdateRequest;
import com.example.animequiz.dto.UserRequest;
import com.example.animequiz.entity.User;
import com.example.animequiz.entity.UserDto;
import com.example.animequiz.service.AuthService;
import com.example.animequiz.service.StorageService;
import com.example.animequiz.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
@Slf4j
public class UserController {

    private final UserService userService;
    private final StorageService storageService;
    private final AuthService authService;

    @Autowired
    public UserController(UserService userService, StorageService storageService, AuthService authService) {
        this.userService = userService;
        this.storageService = storageService;
        this.authService = authService;
    }

    @ResponseBody
    @GetMapping("/getUsers")
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserDto> getUsers() {
        return userService.getUsers().stream().map(this::toDto).collect(Collectors.toList());
    }

    @ResponseBody
    @GetMapping("/getAdminInfo")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> getAdminInfo(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return Map.of("authenticated", false);
        }

        String username = auth.getName();

        User admin = userService.getUsers().stream()
                .filter(u -> u.getUsername().equals(username))
                .findFirst()
                .orElse(null);

        String displayName = (admin != null && admin.getDisplayName() != null) ? admin.getDisplayName() : username;
        String role = "USER";
        if (admin != null
                && admin.getAuthorities() != null
                && !admin.getAuthorities().isEmpty()) {
            role = admin.getAuthorities().get(0).getAuthority().replace("ROLE_", "");
        }

        boolean enabled = admin != null && admin.isEnabled();

        String avatarKey = "u_" + username;
        boolean hasAvatar = storageService.findAvatar(avatarKey).isPresent();
        String avatarUrl = hasAvatar ? "/profile/avatar?user=" + username : "/server-images/anime-logo.png";

        return Map.of(
                "username", username,
                "displayName", displayName,
                "avatarUrl", avatarUrl,
                "role", role.toUpperCase(),
                "enabled", enabled,
                "authenticated", true);
    }

    private UserDto toDto(User user) {
        String role = "user";

        if (user.getAuthorities() != null && !user.getAuthorities().isEmpty()) {
            role = user.getAuthorities().get(0).getAuthority().replace("ROLE_", "");
        }

        String avatarKey = "u_" + user.getUsername();
        boolean hasAvatar = storageService.findAvatar(avatarKey).isPresent();

        String avatarUrl = hasAvatar ? "/profile/avatar?user=" + user.getUsername() : "/server-images/anime-logo.png";

        return new UserDto(user.getUsername(), user.getDisplayName(), user.isEnabled(), role, avatarUrl);
    }

    @ResponseBody
    @DeleteMapping("/deleteUser")
    @PreAuthorize("(hasRole('ADMIN') and authentication.name != #request.username)"
            + " or (hasRole('USER') and authentication.name == #request.username)")
    public Map<String, String> deleteUser(@RequestBody UserRequest request) {
        userService.deleteUser(request.username());
        return Map.of("message", "Пользователь успешно удалён");
    }

    @ResponseBody
    @PostMapping("/createUser")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> createUser(@RequestBody UserRequest userDto) {
        userService.createUser(userDto);
        return Map.of("message", "Пользователь успешно создан");
    }

    @ResponseBody
    @PostMapping("/editUser")
    @PreAuthorize("hasRole('ADMIN') or authentication.name == #userDto.username")
    public Map<String, Object> editUser(
            @RequestBody UserRequest userDto, Authentication currentAuth, HttpServletRequest request) {
        if (currentAuth == null
                || !currentAuth.isAuthenticated()
                || currentAuth instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("Требуется аутентификация");
        }

        boolean isAdmin = currentAuth.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        boolean isSelfUpdate = currentAuth.getName().equals(userDto.username());

        if (!isAdmin && !isSelfUpdate) {
            throw new AccessDeniedException("Недостаточно прав");
        }

        if (isAdmin && !isSelfUpdate) {
            userService.updateUserByAdmin(new AdminUpdateRequest(
                    userDto.username(), userDto.displayName(), userDto.enabled(), userDto.role()));
        } else {
            userService.updateSelfProfile(new SelfUpdateRequest(userDto.username(), userDto.displayName()));

            userService.getUser(userDto.username()).ifPresent(user -> {
                UsernamePasswordAuthenticationToken newAuth = authService.refreshAuthentication(currentAuth, user);
                authService.updateSecurityContext(newAuth, request);
            });
        }

        return Map.of("message", "Пользователь обновлён", "isSelfUpdate", isSelfUpdate);
    }
}
