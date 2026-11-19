package com.example.animequiz.service.ratelimit;

public final class WaitTime {

    private WaitTime() {}

    public static long seconds(long nanos) {
        long seconds = (nanos + 999_999_999L) / 1_000_000_000L;
        return Math.max(1, seconds);
    }

    public static String text(long seconds) {
        if (seconds < 60) {
            return seconds + " сек";
        }
        return (seconds + 59) / 60 + " мин";
    }

    public static String message(long seconds) {
        if (seconds < 1) {
            return "Слишком много попыток. Попробуйте позже";
        }
        return "Слишком много попыток. Повторите через " + text(seconds);
    }
}
