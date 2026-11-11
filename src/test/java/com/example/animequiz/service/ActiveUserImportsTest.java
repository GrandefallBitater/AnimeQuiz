package com.example.animequiz.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActiveUserImportsTest {

    private long now = 1_000_000L;

    private final ActiveUserImports activeImports = new ActiveUserImports(() -> now);

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

    @Test
    void heartbeatKeepsALongImportRunning() {
        activeImports.start("alice");

        for (int page = 0; page < 10; page++) {
            now += ActiveUserImports.STALE_AFTER_MILLIS - 1;
            activeImports.touch("alice");
            activeImports.purgeStale();
        }

        assertThat(activeImports.isRunning("alice")).isTrue();
    }

    @Test
    void importWithoutProgressIsPurged() {
        activeImports.start("alice");
        now += ActiveUserImports.STALE_AFTER_MILLIS + 1;

        activeImports.purgeStale();

        assertThat(activeImports.isRunning("alice")).isFalse();
    }

    @Test
    void touchDoesNotResurrectFinishedImports() {
        activeImports.start("alice");
        activeImports.finish("alice");

        activeImports.touch("alice");

        assertThat(activeImports.isRunning("alice")).isFalse();
    }
}
