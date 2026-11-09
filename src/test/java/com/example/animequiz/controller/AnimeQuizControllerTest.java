package com.example.animequiz.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.animequiz.service.AnimeQuizService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AnimeQuizControllerTest {

    @Mock
    private AnimeQuizService animeQuizService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AnimeQuizController(animeQuizService))
                .build();
    }

    @Test
    void showsTwoRandomImages() throws Exception {
        when(animeQuizService.getRandomImages()).thenReturn(List.of("one", "two"));

        mvc.perform(get("/test"))
                .andExpect(status().isOk())
                .andExpect(view().name("image-choice"))
                .andExpect(model().attribute("images", List.of("one", "two")))
                .andExpect(model().attribute("chosen", (Object) null));
    }

    @Test
    void remembersChosenImage() throws Exception {
        when(animeQuizService.getRandomImages()).thenReturn(List.of("one", "two"));

        mvc.perform(post("/choose").param("index", "1"))
                .andExpect(status().isOk())
                .andExpect(view().name("image-choice"))
                .andExpect(model().attribute("chosen", 1))
                .andExpect(model().attribute("images", List.of("one", "two")));
    }
}
