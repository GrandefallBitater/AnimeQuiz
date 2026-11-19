package com.example.animequiz.securityConfig;

import com.example.animequiz.service.ratelimit.Policy;
import com.example.animequiz.service.ratelimit.RateLimitExceededException;
import com.example.animequiz.service.ratelimit.RateLimitService;
import com.example.animequiz.service.ratelimit.WaitTime;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.EstimationProbe;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

public class RateLimitedAuthenticationProvider extends DaoAuthenticationProvider {

    private final RateLimitService rateLimits;

    public RateLimitedAuthenticationProvider(
            UserDetailsService userDetailsService, PasswordEncoder passwordEncoder, RateLimitService rateLimits) {
        super(userDetailsService);
        setPasswordEncoder(passwordEncoder);
        this.rateLimits = rateLimits;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName();
        String address = remoteAddress(authentication);
        Bucket usernameBucket = rateLimits.bucket(Policy.LOGIN_USERNAME, username);
        Bucket addressBucket = address == null ? null : rateLimits.bucket(Policy.LOGIN_IP, address);

        rejectWhenExhausted(usernameBucket, addressBucket);

        try {
            Authentication success = super.authenticate(authentication);
            rateLimits.reset(Policy.LOGIN_USERNAME, username);
            return success;
        } catch (AuthenticationException failure) {
            usernameBucket.tryConsume(1);
            if (addressBucket != null) {
                addressBucket.tryConsume(1);
            }
            throw failure;
        }
    }

    private static void rejectWhenExhausted(Bucket usernameBucket, Bucket addressBucket) {
        long waitNanos = Math.max(waitNanos(usernameBucket), waitNanos(addressBucket));
        if (waitNanos > 0) {
            throw new RateLimitExceededException(WaitTime.seconds(waitNanos));
        }
    }

    private static long waitNanos(Bucket bucket) {
        if (bucket == null) {
            return 0;
        }
        EstimationProbe probe = bucket.estimateAbilityToConsume(1);
        return probe.canBeConsumed() ? 0 : probe.getNanosToWaitForRefill();
    }

    private static String remoteAddress(Authentication authentication) {
        Object details = authentication.getDetails();
        if (details instanceof WebAuthenticationDetails webDetails) {
            return webDetails.getRemoteAddress();
        }
        return null;
    }
}
