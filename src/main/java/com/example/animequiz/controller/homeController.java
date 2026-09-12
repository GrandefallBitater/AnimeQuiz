package com.example.animequiz.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.ui.Model;

@Controller
public class homeController {

    public homeController() {}

    @GetMapping("/")
    public String showHome(Model model, Authentication authentication,
                           @CookieValue(value = "CustomUserName", defaultValue = "guest") String CustomUserName) {
        String username = (authentication != null) && authentication.isAuthenticated() ? authentication.getName() : CustomUserName;
        model.addAttribute("user", username);
        return "home";
    }
}
