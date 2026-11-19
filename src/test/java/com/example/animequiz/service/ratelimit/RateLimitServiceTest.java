package com.example.animequiz.service.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bucket4j.Bucket;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimitServiceTest {

    private static final RateLimitProperties.Rule LOGIN = new RateLimitProperties.Rule(2, Duration.ofMinutes(15));

    private static RateLimitService service() {
        RateLimitProperties properties = new RateLimitProperties(
                new RateLimitProperties.Login(LOGIN, LOGIN),
                new RateLimitProperties.Register(new RateLimitProperties.Rule(3, Duration.ofHours(1))),
                new RateLimitProperties.Imports(new RateLimitProperties.Rule(15, Duration.ofHours(1))));
        return new RateLimitService(properties);
    }

    @Test
    void differentPoliciesKeepSeparateBucketsForTheSameKey() {
        RateLimitService rateLimits = service();

        Bucket username = rateLimits.bucket(Policy.LOGIN_USERNAME, "alice");
        Bucket address = rateLimits.bucket(Policy.LOGIN_IP, "alice");
        assertThat(username.tryConsume(2)).isTrue();

        assertThat(username.getAvailableTokens()).isZero();
        assertThat(address.getAvailableTokens()).isEqualTo(2);
    }

    @Test
    void differentKeysInsideOnePolicyAreIndependent() {
        RateLimitService rateLimits = service();

        Bucket alice = rateLimits.bucket(Policy.LOGIN_USERNAME, "alice");
        Bucket bob = rateLimits.bucket(Policy.LOGIN_USERNAME, "bob");
        alice.tryConsume(2);

        assertThat(alice.getAvailableTokens()).isZero();
        assertThat(bob.getAvailableTokens()).isEqualTo(2);
    }

    @Test
    void samePolicyAndKeyReturnTheSameBucket() {
        RateLimitService rateLimits = service();

        assertThat(rateLimits.bucket(Policy.LOGIN_USERNAME, "alice"))
                .isSameAs(rateLimits.bucket(Policy.LOGIN_USERNAME, "alice"));
    }

    @Test
    void resetRestoresFullCapacity() {
        RateLimitService rateLimits = service();
        rateLimits.bucket(Policy.LOGIN_USERNAME, "alice").tryConsume(2);

        rateLimits.reset(Policy.LOGIN_USERNAME, "alice");

        assertThat(rateLimits.bucket(Policy.LOGIN_USERNAME, "alice").getAvailableTokens())
                .isEqualTo(2);
    }

    @Test
    void rejectsRulesWithNonPositiveCapacityOrWindow() {
        assertThatThrownBy(() -> new RateLimitProperties.Rule(0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Rule(1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Rule(1, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPropertiesWithoutConfiguredSection() {
        assertThatThrownBy(() -> new RateLimitProperties(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
