package com.example.animequiz.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Service;

@Service
public class AnimeQuizService {
    // ⚠️ Замените ссылки на реальные URL аниме-картинок.
    // Сейчас используются стабильные placeholder-изображения.
    private static final List<String> ANIME_IMAGES = List.of(
            "https://picsum.photos/seed/anime1/300/400",
            "https://picsum.photos/seed/anime2/300/400",
            "https://picsum.photos/seed/anime3/300/400",
            "https://picsum.photos/seed/anime4/300/400",
            "https://picsum.photos/seed/anime5/300/400");

    public List<String> getRandomImages() {
        List<String> shuffled = new ArrayList<>(ANIME_IMAGES);
        Collections.shuffle(shuffled, new Random());
        return shuffled.subList(0, Math.min(2, shuffled.size()));
    }
}
