package com.example.animequiz.service;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ActiveUserImports {

    static final long STALE_AFTER_MILLIS = Duration.ofMinutes(10).toMillis();

    record UserData(long time, long jobId, boolean running) {}

    private final ConcurrentHashMap<String, UserData> active = new ConcurrentHashMap<>();

    private final LongSupplier clock;

    public ActiveUserImports() {
        this(System::currentTimeMillis);
    }

    ActiveUserImports(LongSupplier clock) {
        this.clock = clock;
    }

    public boolean tryStart(String username, long jobId) {
        long now = clock.getAsLong();
        UserData started = new UserData(now, jobId, true);
        UserData stored = active.compute(username, (key, current) -> isBusy(current, now) ? current : started);
        return Objects.equals(stored.jobId(), jobId) && stored.running();
    }

    public void touch(String username) {
        active.computeIfPresent(username, (key, data) -> new UserData(clock.getAsLong(), data.jobId(), data.running()));
    }

    public void finish(String username) {
        active.computeIfPresent(username, (key, data) -> new UserData(clock.getAsLong(), data.jobId(), false));
    }

    public boolean isRunning(String username) {
        UserData data = active.get(username);
        return data != null && data.running() && !isStale(data, clock.getAsLong());
    }

    public Optional<Long> runningJobId(String username) {
        UserData data = active.get(username);
        if (data == null || !data.running() || isStale(data, clock.getAsLong())) {
            return Optional.empty();
        }
        return Optional.of(data.jobId());
    }

    public Optional<Long> lastJobId(String username) {
        UserData data = active.get(username);
        if (data == null || isStale(data, clock.getAsLong())) {
            return Optional.empty();
        }
        return Optional.of(data.jobId());
    }

    @Scheduled(fixedDelayString = "PT5M")
    void purgeStale() {
        long now = clock.getAsLong();
        active.entrySet().removeIf(entry -> isStale(entry.getValue(), now));
    }

    private static boolean isBusy(UserData data, long now) {
        return data != null && data.running() && !isStale(data, now);
    }

    private static boolean isStale(UserData data, long now) {
        return now - data.time() > STALE_AFTER_MILLIS;
    }
}
