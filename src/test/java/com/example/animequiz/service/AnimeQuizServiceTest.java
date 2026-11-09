package com.example.animequiz.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnimeQuizServiceTest {

    private static final Set<String> CATALOG = Set.of(
            "https://picsum.photos/seed/anime1/300/400",
            "https://picsum.photos/seed/anime2/300/400",
            "https://picsum.photos/seed/anime3/300/400",
            "https://picsum.photos/seed/anime4/300/400",
            "https://picsum.photos/seed/anime5/300/400");

    private final AnimeQuizService service = new AnimeQuizService();

    @Test
    void returnsTwoDifferentImagesFromCatalog() {
        for (int attempt = 0; attempt < 20; attempt++) {
            List<String> images = service.getRandomImages();

            assertThat(images).hasSize(2);
            assertThat(new HashSet<>(images)).hasSize(2);
            assertThat(CATALOG).containsAll(images);
        }
    }
}
