package com.example.animequiz.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActiveUserImportsTest {

    private long now = 1_000_000L;

    private final ActiveUserImports activeImports = new ActiveUserImports(() -> now);

    @Test
    void tracksEachUserIndependently() {
        assertThat(activeImports.tryStart("alice", 1L)).isTrue();
        assertThat(activeImports.tryStart("bob", 2L)).isTrue();

        assertThat(activeImports.isRunning("alice")).isTrue();
        assertThat(activeImports.isRunning("bob")).isTrue();
        assertThat(activeImports.isRunning("carol")).isFalse();

        activeImports.finish("alice");

        assertThat(activeImports.isRunning("alice")).isFalse();
        assertThat(activeImports.isRunning("bob")).isTrue();
    }

    @Test
    void tryStartRejectsASecondImportOfTheSameUser() {
        assertThat(activeImports.tryStart("alice", 1L)).isTrue();

        assertThat(activeImports.tryStart("alice", 2L)).isFalse();

        assertThat(activeImports.runningJobId("alice")).contains(1L);
    }

    @Test
    void tryStartTakesOverAStaleImport() {
        assertThat(activeImports.tryStart("alice", 1L)).isTrue();

        now += ActiveUserImports.STALE_AFTER_MILLIS + 1;

        assertThat(activeImports.tryStart("alice", 2L)).isTrue();
        assertThat(activeImports.runningJobId("alice")).contains(2L);
    }

    @Test
    void finishedImportKeepsItsJobIdForLatePolling() {
        assertThat(activeImports.tryStart("alice", 1L)).isTrue();

        activeImports.finish("alice");

        assertThat(activeImports.isRunning("alice")).isFalse();
        assertThat(activeImports.runningJobId("alice")).isEmpty();
        assertThat(activeImports.lastJobId("alice")).contains(1L);
    }

    @Test
    void tryStartAllowsANewImportAfterFinish() {
        assertThat(activeImports.tryStart("alice", 1L)).isTrue();
        activeImports.finish("alice");

        assertThat(activeImports.tryStart("alice", 2L)).isTrue();
        assertThat(activeImports.lastJobId("alice")).contains(2L);
    }

    @Test
    void unknownUserHasNoJob() {
        assertThat(activeImports.isRunning("ghost")).isFalse();
        assertThat(activeImports.runningJobId("ghost")).isEmpty();
        assertThat(activeImports.lastJobId("ghost")).isEmpty();
    }

    @Test
    void finishingAnUnknownUserIsHarmless() {
        activeImports.finish("ghost");

        assertThat(activeImports.isRunning("ghost")).isFalse();
    }

    @Test
    void heartbeatKeepsALongImportRunning() {
        activeImports.tryStart("alice", 1L);

        for (int page = 0; page < 10; page++) {
            now += ActiveUserImports.STALE_AFTER_MILLIS - 1;
            activeImports.touch("alice");
            activeImports.purgeStale();
        }

        assertThat(activeImports.isRunning("alice")).isTrue();
        assertThat(activeImports.runningJobId("alice")).contains(1L);
    }

    @Test
    void importWithoutProgressIsPurged() {
        activeImports.tryStart("alice", 1L);
        now += ActiveUserImports.STALE_AFTER_MILLIS + 1;

        activeImports.purgeStale();

        assertThat(activeImports.isRunning("alice")).isFalse();
        assertThat(activeImports.runningJobId("alice")).isEmpty();
    }

    @Test
    void touchDoesNotResurrectFinishedImports() {
        activeImports.tryStart("alice", 1L);
        activeImports.finish("alice");

        activeImports.touch("alice");

        assertThat(activeImports.isRunning("alice")).isFalse();
    }
}
