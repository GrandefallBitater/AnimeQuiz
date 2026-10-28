package com.example.animequiz.service;

import com.example.animequiz.controller.UserController;
import com.example.animequiz.entity.Authority;
import com.example.animequiz.entity.User;
import com.example.animequiz.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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
            return "что-то пошло не так, операция отменена: " + e.getMessage();
        }
        return "удаление прошло успешно";
    }

    public String createUser(@RequestBody UserController.UserRequest userDto) {
        User user = new User();
        user.setUsername(userDto.username());
        user.setDisplayName(userDto.displayName());
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
            return "что-то пошло не так: " + e.getMessage();
        }
        return "пользователь успешно добавлен";
    }

    @Transactional
    public  String updateUser(@RequestBody UserController.UserRequest userDto, Boolean isSelfUpdate) {
        User user = userRepository.findById(userDto.username())
                .orElseThrow(() -> new EntityNotFoundException("Пользователь не найден"));

        user.setDisplayName(userDto.displayName());
        user.setEnabled(userDto.enabled());

        String roleName = userDto.role() != null ? userDto.role().toUpperCase() : "USER";
        String targetAuthority = "ROLE_" + roleName;

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
        if(!isSelfUpdate) {
            sessionService.expireUserSessions(userDto.username());
        }
        return "Пользователь обновлён";
    }

    public Optional<User> getUser(String username) {
        try {
             return userRepository.findById(username);
        }catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Оставляет только роли из белого списка; всё остальное (включая мусор
     * и попытки подставить "ROLE_SUPERADMIN") превращается в USER.
     * Вызывается только из createUser, доступного администратору.
     */
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
