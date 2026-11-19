package com.example.animequiz.securityConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import com.example.animequiz.service.ratelimit.RateLimitExceededException;
import com.example.animequiz.service.ratelimit.RateLimitProperties;
import com.example.animequiz.service.ratelimit.RateLimitService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

@ExtendWith(MockitoExtension.class)
class RateLimitedAuthenticationProviderTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final UserDetails ALICE = User.withUsername("alice")
            .password(ENCODER.encode("secret"))
            .roles("USER")
            .build();

    @Mock
    private UserDetailsService userDetailsService;

    @BeforeEach
    void stubUser() {
        lenient().when(userDetailsService.loadUserByUsername(anyString())).thenReturn(ALICE);
    }

    @Test
    void blocksFurtherAttemptsAfterLimitIsSpent() {
        RateLimitedAuthenticationProvider provider = provider(2, 2);

        assertThatThrownBy(() -> provider.authenticate(token("alice", "wrong", "10.0.0.1")))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> provider.authenticate(token("alice", "wrong", "10.0.0.1")))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> provider.authenticate(token("alice", "secret", "10.0.0.1")))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(failure -> assertThat(((RateLimitExceededException) failure).getRetryAfterSeconds())
                        .isPositive());
    }

    @Test
    void successfulLoginResetsUsernameCounter() {
        RateLimitedAuthenticationProvider provider = provider(2, 5);

        assertThatThrownBy(() -> provider.authenticate(token("alice", "wrong", "10.0.0.1")))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(provider.authenticate(token("alice", "secret", "10.0.0.1"))).isNotNull();

        assertThatThrownBy(() -> provider.authenticate(token("alice", "wrong", "10.0.0.1")))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> provider.authenticate(token("alice", "wrong", "10.0.0.1")))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> provider.authenticate(token("alice", "secret", "10.0.0.1")))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void ipBucketIsSkippedWithoutWebAuthenticationDetails() {
        RateLimitedAuthenticationProvider provider = provider(5, 1);

        assertThatThrownBy(() -> provider.authenticate(token("alice", "wrong", null)))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> provider.authenticate(token("alice", "wrong", null)))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void ipBucketBlocksAttemptsAcrossDifferentUsernames() {
        RateLimitedAuthenticationProvider provider = provider(5, 1);

        assertThatThrownBy(() -> provider.authenticate(token("alice", "wrong", "10.0.0.1")))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> provider.authenticate(token("bob", "wrong", "10.0.0.1")))
                .isInstanceOf(RateLimitExceededException.class);
    }

    private RateLimitedAuthenticationProvider provider(int usernameCapacity, int ipCapacity) {
        RateLimitProperties properties = new RateLimitProperties(
                new RateLimitProperties.Login(
                        new RateLimitProperties.Rule(usernameCapacity, Duration.ofMinutes(15)),
                        new RateLimitProperties.Rule(ipCapacity, Duration.ofMinutes(15))),
                new RateLimitProperties.Register(new RateLimitProperties.Rule(3, Duration.ofHours(1))),
                new RateLimitProperties.Imports(new RateLimitProperties.Rule(15, Duration.ofHours(1))));
        return new RateLimitedAuthenticationProvider(userDetailsService, ENCODER, new RateLimitService(properties));
    }

    private static Authentication token(String username, String password, String address) {
        UsernamePasswordAuthenticationToken token =
                UsernamePasswordAuthenticationToken.unauthenticated(username, password);
        if (address != null) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr(address);
            token.setDetails(new WebAuthenticationDetails(request));
        }
        return token;
    }
}
