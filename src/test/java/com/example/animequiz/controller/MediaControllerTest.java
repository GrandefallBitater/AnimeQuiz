package com.example.animequiz.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.animequiz.service.PosterStorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MediaControllerTest {

    @Mock
    private PosterStorageService posterStorage;

    @TempDir
    private Path tempDir;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new MediaController(posterStorage))
                .build();
    }

    @Test
    void servesPosterWithLongCache() throws Exception {
        Path poster = tempDir.resolve("abcd1234.png");
        Files.write(poster, new byte[] {1, 2, 3});
        when(posterStorage.resolve("abcd1234", "png")).thenReturn(poster);

        mvc.perform(get("/media/posters/abcd1234.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", containsString("max-age=31536000")))
                .andExpect(header().string("Cache-Control", containsString("immutable")));
    }

    @Test
    void servesWebpPoster() throws Exception {
        Path poster = tempDir.resolve("abcd1234.webp");
        Files.write(poster, new byte[] {1, 2, 3});
        when(posterStorage.resolve("abcd1234", "webp")).thenReturn(poster);

        mvc.perform(get("/media/posters/abcd1234.webp"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType("image/webp")));
    }

    @Test
    void returnsNotFoundWhenPosterDoesNotExist() throws Exception {
        Path poster = tempDir.resolve("missing.png");
        when(posterStorage.resolve("abcd1234", "png")).thenReturn(poster);

        mvc.perform(get("/media/posters/abcd1234.png")).andExpect(status().isNotFound());
    }

    @Test
    void returnsPosterBytes() throws Exception {
        Path poster = tempDir.resolve("abcd1234.jpg");
        byte[] content = {4, 5, 6};
        Files.write(poster, content);
        when(posterStorage.resolve("abcd1234", "jpg")).thenReturn(poster);

        byte[] body = mvc.perform(get("/media/posters/abcd1234.jpg"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(body).isEqualTo(content);
    }
}
