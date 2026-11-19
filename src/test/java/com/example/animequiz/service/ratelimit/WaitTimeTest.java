package com.example.animequiz.service.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WaitTimeTest {

    @Test
    void keepsSecondsBelowOneMinute() {
        assertThat(WaitTime.text(59)).isEqualTo("59 сек");
    }

    @Test
    void roundsMinutesUp() {
        assertThat(WaitTime.text(60)).isEqualTo("1 мин");
        assertThat(WaitTime.text(61)).isEqualTo("2 мин");
    }

    @Test
    void clampsNanosToAtLeastOneSecond() {
        assertThat(WaitTime.seconds(0)).isEqualTo(1);
        assertThat(WaitTime.seconds(999_999_999L)).isEqualTo(1);
        assertThat(WaitTime.seconds(2_500_000_000L)).isEqualTo(3);
    }

    @Test
    void fallsBackForNonPositiveSeconds() {
        assertThat(WaitTime.message(0)).isEqualTo("Слишком много попыток. Попробуйте позже");
        assertThat(WaitTime.message(42)).isEqualTo("Слишком много попыток. Повторите через 42 сек");
    }
}
