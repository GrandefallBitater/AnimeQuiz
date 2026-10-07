package com.example.animequiz.service.ratelimit;

import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("UnstableApiUsage")
@Slf4j
@Component
public class SourceRateLimiter {

    private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    public void acquire(String source) {
        RateLimiter rl = limiters.computeIfAbsent(source,
                s -> RateLimiter.create(limits(s)));
        rl.acquire();
    }

    private static double limits(String source) {
        return switch (source) {
            case "shikimori" -> 1.2;
            case "mal"       -> 2.5;
            case "yummy"     -> 1.5;
            case "poster"    -> 10.0;
            default          -> 1.0;
        };
    }
}