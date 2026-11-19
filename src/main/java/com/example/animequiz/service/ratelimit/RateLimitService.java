package com.example.animequiz.service.ratelimit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

    private static final Duration IDLE_TTL = Duration.ofHours(1);
    private static final long MAX_BUCKETS = 100_000L;

    private final RateLimitProperties properties;
    private final Cache<String, Bucket> buckets;

    public RateLimitService(RateLimitProperties properties) {
        this.properties = properties;
        this.buckets = CacheBuilder.newBuilder()
                .expireAfterAccess(IDLE_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    public Bucket bucket(Policy policy, String key) {
        String cacheKey = policy.name() + ':' + key;
        try {
            return buckets.get(cacheKey, () -> create(policy));
        } catch (ExecutionException e) {
            throw new IllegalStateException("Не удалось создать бакет " + cacheKey, e);
        }
    }

    public void reset(Policy policy, String key) {
        buckets.invalidate(policy.name() + ':' + key);
    }

    private Bucket create(Policy policy) {
        RateLimitProperties.Rule rule = rule(policy);
        Bandwidth limit = Bandwidth.builder()
                .capacity(rule.capacity())
                .refillIntervally(rule.capacity(), rule.window())
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private RateLimitProperties.Rule rule(Policy policy) {
        return switch (policy) {
            case LOGIN_USERNAME -> properties.login().username();
            case LOGIN_IP -> properties.login().ip();
            case REGISTER_IP -> properties.register().ip();
            case IMPORT_USER -> properties.imports().user();
        };
    }
}
