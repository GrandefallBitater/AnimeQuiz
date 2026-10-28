package com.example.animequiz.service;

import com.example.animequiz.controller.UserController;
import com.example.animequiz.entity.Authority;
import com.example.animequiz.entity.User;
import com.example.animequiz.repository.UserRepository;
import com.example.animequiz.validation.InputValidation;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class UserService {

    private static final Set<String> ALLOWED_ROLES = Set.of("USER", "ADMIN");

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final SessionService sessionService;

    @Autowired
    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder, StorageService storageService,
                       SessionService sessionService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.storageService = storageService;
        this.sessionService = sessionService;
    }

    public List<User> getUsers() {
        return userRepository.findAll();
    }

    public String deleteUser(String username) {
        try {
            userRepository.deleteById(username);
            String key = "u_" + username;
            String sk = StorageService.sanitizeKey(key);
            storageService.deleteAvatar(sk);
        } catch (Exception e) {
            log.error("Не удалось удалить пользователя {}", InputValidation.forLog(username), e);
            return "Не удалось удалить пользователя";
        }
        return "удаление прошло успешно";
    }

    public String createUser(UserController.UserRequest userDto) {
        final String username;
        final String displayName;

        try {
            username = InputValidation.normalizeUsername(userDto.username());
            String requestedDisplayName = userDto.displayName();
            displayName = InputValidation.normalizeDisplayName(
                    requestedDisplayName == null || requestedDisplayName.isBlank()
                            ? username : requestedDisplayName);
            if (userDto.password() == null || userDto.password().length() < 6) {
                throw new IllegalArgumentException("Пароль должен быть не короче 6 символов");
            }
        } catch (IllegalArgumentException e) {
            return "что-то пошло не так: " + e.getMessage();
        }

        User user = new User();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setEnabled(userDto.enabled());

        user.setPassword(passwordEncoder.encode(userDto.password()));

        Authority authority = new Authority();
        authority.setAuthority("ROLE_" + resolveRole(userDto.role()));
        authority.setUser(user);
        if (user.getAuthorities() == null) {
            user.setAuthorities(new ArrayList<>());
        }
        user.getAuthorities().add(authority);

        try {
            userRepository.save(user);
        } catch (Exception e) {
            log.error("Не удалось создать пользователя {}", InputValidation.forLog(username), e);
            return "что-то пошло не так: Не удалось создать пользователя";
        }
        return "пользователь успешно добавлен";
    }

    @Transactional
    public String updateSelfProfile(UserController.SelfUpdateRequest request) {
        User user = userRepository.findById(request.username())
                .orElseThrow(() -> new EntityNotFoundException("Пользователь не найден"));

        String displayName = InputValidation.normalizeDisplayName(request.displayName());
        user.setDisplayName(displayName);

        return "Профиль обновлён";
    }

    @Transactional
    public String updateUserByAdmin(UserController.AdminUpdateRequest request) {
        User user = userRepository.findById(request.username())
                .orElseThrow(() -> new EntityNotFoundException("Пользователь не найден"));

        String displayName = InputValidation.normalizeDisplayName(request.displayName());
        user.setDisplayName(displayName);
        user.setEnabled(request.enabled());

        String targetAuthority = "ROLE_" + resolveRole(request.role());

        if (user.getAuthorities() == null) {
            user.setAuthorities(new ArrayList<>());
        }

        boolean alreadyHasRole = user.getAuthorities().stream()
                .anyMatch(auth -> targetAuthority.equals(auth.getAuthority()));

        if (!alreadyHasRole) {
            user.getAuthorities().clear();

            Authority authority = new Authority();
            authority.setAuthority(targetAuthority);
            authority.setUser(user);

            user.getAuthorities().add(authority);
        }

        sessionService.expireUserSessions(request.username());

        return "Пользователь обновлён";
    }

    public Optional<User> getUser(String username) {
        try {
             return userRepository.findById(username);
        }catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String resolveRole(String requestedRole) {
        if (requestedRole == null) {
            return "USER";
        }
        String role = requestedRole.trim().toUpperCase(Locale.ROOT);
        if (role.startsWith("ROLE_")) {
            role = role.substring("ROLE_".length());
        }
        return ALLOWED_ROLES.contains(role) ? role : "USER";
    }
}
