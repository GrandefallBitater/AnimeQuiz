package com.example.animequiz.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.ui.Model;

@Controller
public class homeController {

    public homeController() {}

    @GetMapping("/")
    public String showHome(Model model) {
        model.addAttribute("user", "unknow");
        return "home";
    }
}
