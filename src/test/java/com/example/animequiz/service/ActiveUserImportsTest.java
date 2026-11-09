package com.example.animequiz.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActiveUserImportsTest {

    private final ActiveUserImports activeImports = new ActiveUserImports();

    @Test
    void tracksEachUserIndependently() {
        activeImports.start("alice");
        activeImports.start("bob");

        assertThat(activeImports.isRunning("alice")).isTrue();
        assertThat(activeImports.isRunning("bob")).isTrue();
        assertThat(activeImports.isRunning("carol")).isFalse();

        activeImports.finish("alice");

        assertThat(activeImports.isRunning("alice")).isFalse();
        assertThat(activeImports.isRunning("bob")).isTrue();
    }

    @Test
    void finishingAnUnknownUserIsHarmless() {
        activeImports.finish("ghost");

        assertThat(activeImports.isRunning("ghost")).isFalse();
    }
}
