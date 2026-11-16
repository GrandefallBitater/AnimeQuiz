package com.example.animequiz.controller;

import com.example.animequiz.service.AnimeQuizService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AnimeQuizController {

    private final AnimeQuizService AnimeQuizService;

    @Autowired
    public AnimeQuizController(AnimeQuizService AnimeQuizService) {
        this.AnimeQuizService = AnimeQuizService;
    }

    @GetMapping("/test")
    public String showChoice(Model model) {
        model.addAttribute("images", AnimeQuizService.getRandomImages());
        model.addAttribute("chosen", null);
        return "image-choice";
    }

    @PostMapping("/choose")
    public String chooseImage(@RequestParam int index, Model model) {
        // index: 0 или 1 (соответствует порядку картинок)
        model.addAttribute("chosen", index);
        model.addAttribute("images", AnimeQuizService.getRandomImages());
        return "image-choice";
    }
}
