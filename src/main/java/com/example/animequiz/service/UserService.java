package com.example.animequiz.service;

import com.example.animequiz.controller.UserController;
import com.example.animequiz.entity.Authority;
import com.example.animequiz.entity.User;
import com.example.animequiz.exceptions.ConflictException;
import com.example.animequiz.exceptions.InvalidInputException;
import com.example.animequiz.exceptions.NotFoundException;
import com.example.animequiz.repository.UserRepository;
import com.example.animequiz.validation.InputValidation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
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

    public void deleteUser(String username) {
        User user = userRepository.findByUsername(username).orElseThrow(() -> new NotFoundException("Пользователь не найден"));
        userRepository.deleteById(user.getUsername());
        sessionService.expireUserSessions(username);
        String key = "u_" + username;
        String sk = StorageService.sanitizeKey(key);
        try {
            storageService.deleteAvatar(sk);
        } catch (IOException e) {
            log.warn("Удаление аватара прошло неудачно", e);
        }
    }

    public void createUser(UserController.UserRequest userDto) {
        final String username;
        final String displayName;

        username = InputValidation.normalizeUsername(userDto.username());
        String requestedDisplayName = userDto.displayName();
        displayName = InputValidation.normalizeDisplayName(
                requestedDisplayName == null || requestedDisplayName.isBlank()
                        ? username : requestedDisplayName);
        if (userDto.password() == null || userDto.password().length() < 6) {
            throw new InvalidInputException("Пароль должен быть не короче 6 символов");
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
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Никнейм уже занят", e);
        }
    }

    @Transactional
    public void updateSelfProfile(UserController.SelfUpdateRequest request) {
        User user = userRepository.findById(request.username())
                .orElseThrow(() -> new NotFoundException("Пользователь не найден"));

        String displayName = InputValidation.normalizeDisplayName(request.displayName());
        user.setDisplayName(displayName);
    }

    @Transactional
    public void updateUserByAdmin(UserController.AdminUpdateRequest request) {
        User user = userRepository.findById(request.username())
                .orElseThrow(() -> new NotFoundException("Пользователь не найден"));

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
    }

    public Optional<User> getUser(String username) {
        return userRepository.findById(username);
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
