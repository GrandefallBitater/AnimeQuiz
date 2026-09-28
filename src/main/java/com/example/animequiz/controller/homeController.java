package com.example.animequiz.controller;

import com.example.animequiz.Interfaces.CustomUserDetails;
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
        String username = (authentication != null) && authentication.isAuthenticated() ? "" : CustomUserName;
        if (username.isEmpty()) {
            CustomUserDetails userDetails = null;
            if (authentication != null) {
                userDetails = (CustomUserDetails) authentication.getPrincipal();
            }
            assert userDetails != null;
            username = userDetails.getDisplayName();
        }
        model.addAttribute("user", username);
        return "home";
    }
}
