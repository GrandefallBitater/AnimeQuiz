package com.example.animequiz.service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ActiveUserImports {

    static final long STALE_AFTER_MILLIS = Duration.ofMinutes(10).toMillis();

    private final ConcurrentHashMap<String, Long> active = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public ActiveUserImports() {
        this(System::currentTimeMillis);
    }

    ActiveUserImports(LongSupplier clock) {
        this.clock = clock;
    }

    public void start(String username) {
        active.put(username, clock.getAsLong());
    }

    public void touch(String username) {
        active.computeIfPresent(username, (key, startedAt) -> clock.getAsLong());
    }

    public void finish(String username) {
        active.remove(username);
    }

    public boolean isRunning(String username) {
        Long startedAt = active.get(username);
        return startedAt != null && !isStale(startedAt, clock.getAsLong());
    }

    @Scheduled(fixedDelayString = "PT5M")
    void purgeStale() {
        long now = clock.getAsLong();
        active.entrySet().removeIf(entry -> isStale(entry.getValue(), now));
    }

    private static boolean isStale(long startedAt, long now) {
        return now - startedAt > STALE_AFTER_MILLIS;
    }
}
