package com.example.animequiz.controller;

import com.example.animequiz.entity.User;
import com.example.animequiz.entity.UserDto;
import com.example.animequiz.service.StorageService;
import com.example.animequiz.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
public class UserController {

    private final UserService userService;
    private final StorageService storageService;

    @Autowired
    public UserController(UserService userService, StorageService storageService) {
        this.userService = userService;
        this.storageService = storageService;
    }

    @ResponseBody
    @GetMapping("/getUsers")
    @Secured({"ROLE_ADMIN"})
    public List<UserDto> getUsers() {
        return userService.getUsers().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @ResponseBody
    @GetMapping("/getAdminInfo")
    @Secured({"ROLE_ADMIN"})
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
        if (admin != null && admin.getAuthorities() != null && !admin.getAuthorities().isEmpty()) {
            role = admin.getAuthorities().get(0)
                    .getAuthority()
                    .replace("ROLE_", "");
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
                "authenticated", true
        );
    }

    private UserDto toDto(User user) {
        String role = "user";

        if (user.getAuthorities() != null && !user.getAuthorities().isEmpty()) {
            role = user.getAuthorities().get(0)
                    .getAuthority()
                    .replace("ROLE_", "");
        }

        String avatarKey = "u_" + user.getUsername();
        boolean hasAvatar = storageService.findAvatar(avatarKey).isPresent();

        String avatarUrl = hasAvatar ? "/profile/avatar?user=" + user.getUsername() : "/server-images/anime-logo.png";

        return new UserDto(
                user.getUsername(),
                user.getDisplayName(),
                user.isEnabled(),
                role,
                avatarUrl
        );
    }

    @ResponseBody
    @DeleteMapping("/deleteUser")
    @PreAuthorize("(hasRole('ADMIN') and authentication.name != #request.username)" +
            " or (hasRole('USER') and authentication.name == #request.username)")
    public Map<String, String> deleteUser(@RequestBody UserRequest request) {
        String response;
        try {
            response = userService.deleteUser(request.username);
        } catch (Exception e){
            return Map.of("message", e.getMessage());
        }
        return Map.of("message", response);
    }

    @ResponseBody
    @PostMapping("/createUser")
    @PreAuthorize("!hasRole('ADMIN') or !hasRole('User')")
    public Map<String, String> createUser(@RequestBody UserRequest userDto) {
        String response;
        try {
            response = userService.createUser(userDto);
        } catch (Exception e){
            return Map.of("message", e.getMessage());
        }
        return Map.of("message", response);
    }

    @ResponseBody
    @PostMapping("/editUser")
    @PreAuthorize("hasRole('ADMIN') or authentication.name == #userDto.username")
    public Map<String, Object> editUser(@RequestBody UserRequest userDto,
                                        Authentication currentAuth) {
        String message;
        boolean isSelfUpdate = false;

        try {
            message = userService.updateUser(userDto);

            if (currentAuth != null && currentAuth.getName().equals(userDto.username())) {
                isSelfUpdate = true;

                Optional<User> updatedUser = userService.getUser(userDto.username());
                if (updatedUser.isPresent()) {
                    User user = updatedUser.get();
                    com.example.animequiz.securityConfig.CustomUserDetailsImpl userDetails =
                            new com.example.animequiz.securityConfig.CustomUserDetailsImpl(
                                    user.getUsername(),
                                    user.getPassword(),
                                    user.getDisplayName(),
                                    user.getAuthorities(),
                                    user.isEnabled()
                            );

                    UsernamePasswordAuthenticationToken newAuth = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            currentAuth.getCredentials(),
                            user.getAuthorities()
                    );
                    SecurityContextHolder.getContext().setAuthentication(newAuth);
                }
            }
        } catch (Exception e) {
            return Map.of("message", "Ошибка при обновлении: " + e.getMessage(), "success", false);
        }
        return Map.of(
                "message", message,
                "isSelfUpdate", isSelfUpdate
        );
    }

    public record UserRequest(String username, String displayName, boolean enabled, String role, String password, String avatarUrl) {}
}
