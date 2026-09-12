package com.example.animequiz.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NoArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@NoArgsConstructor
public class profileController {

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
}
