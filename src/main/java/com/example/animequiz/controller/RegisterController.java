package com.example.animequiz.controller;

import com.example.animequiz.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class RegisterController {

    private final UserService userService;

    @Autowired
    public RegisterController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || username.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Никнейм не может быть пустым"));
        }

        if (password == null || password.length() < 6) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Пароль должен быть не короче 6 символов"));
        }
        try {
            String cleanUsername = username.trim();
            UserController.UserRequest userRequest = new UserController.UserRequest(
                    cleanUsername,
                    cleanUsername,
                    true,
                    "USER",
                    password,
                    null
            );
            String serviceResponse = userService.createUser(userRequest);
            if (serviceResponse.startsWith("что-то пошло не так")) {
                return ResponseEntity.badRequest().body(Map.of("message", serviceResponse));
            }
            return ResponseEntity.ok(Map.of("message", "Аккаунт создан! Теперь войдите"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Ошибка при регистрации: " + e.getMessage()));
        }
    }
}