package com.example.animequiz.controller.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.animequiz.entity.greetingsDTO;
import org.junit.jupiter.api.Test;

class GreetingControllerTest {

    private final GreetingController controller = new GreetingController();

    @Test
    void greetsNormalizedLobbyName() {
        assertThat(controller.greeting(new greetingsDTO("  Alice  ")).getName()).isEqualTo("hi Alice");
    }

    @Test
    void fallsBackToGuestForInvalidOrMissingName() {
        assertThat(controller.greeting(new greetingsDTO("<b>Alice</b>")).getName())
                .isEqualTo("hi guest");
        assertThat(controller.greeting(new greetingsDTO("")).getName()).isEqualTo("hi guest");
        assertThat(controller.greeting(null).getName()).isEqualTo("hi guest");
    }

    @Test
    void lobbyPageIsRendered() {
        assertThat(controller.lobby()).isEqualTo("lobby");
    }
}
