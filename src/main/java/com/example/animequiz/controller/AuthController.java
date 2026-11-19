package com.example.animequiz.controller;

import com.example.animequiz.service.ratelimit.WaitTime;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    @GetMapping("/login")
    public String loginPage(
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "retry", required = false) Long retry,
            @RequestParam(value = "message", required = false) String message,
            Model model) {

        if (error != null) {
            switch (error) {
                case "credentials":
                    model.addAttribute("errorMessage", "Неверный никнейм или пароль");
                    break;
                case "rate":
                    model.addAttribute("errorMessage", WaitTime.message(retry == null ? 0 : retry));
                    break;
                case "session":
                    model.addAttribute("errorMessage", "Сессия истекла. Войдите снова");
                    break;
                case "disabled":
                    model.addAttribute("errorMessage", "Вы были забанены, свяжитесь с администратором");
                    break;
                default:
                    model.addAttribute("errorMessage", "Ошибка авторизации");
            }
        }

        if (message != null) {
            model.addAttribute("infoMessage", message);
        }

        return "login";
    }

    @GetMapping("/register")
    public String registerPage(@RequestParam(value = "message", required = false) String message, Model model) {

        if (message != null) {
            model.addAttribute("infoMessage", message);
        }

        return "register";
    }
}
