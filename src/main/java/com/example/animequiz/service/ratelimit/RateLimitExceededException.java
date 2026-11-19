package com.example.animequiz.service.ratelimit;

import org.springframework.security.core.AuthenticationException;

public class RateLimitExceededException extends AuthenticationException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super(WaitTime.message(retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
