package com.example.animequiz.controller;

import com.example.animequiz.dto.UserRequest;
import com.example.animequiz.service.UserService;
import com.example.animequiz.validation.InputValidation;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
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

        final String cleanUsername = InputValidation.normalizeUsername(username);

        UserRequest userRequest = new UserRequest(cleanUsername, cleanUsername, true, "USER", password, null);
        userService.createUser(userRequest);
        return ResponseEntity.ok(Map.of("message", "Аккаунт создан! Теперь войдите"));
    }
}
