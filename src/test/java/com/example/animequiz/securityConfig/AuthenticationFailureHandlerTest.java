package com.example.animequiz.securityConfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.animequiz.service.ratelimit.RateLimitExceededException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;

class AuthenticationFailureHandlerTest {

    private final AuthenticationFailureHandler handler = new AuthenticationFailureHandler();

    @Test
    void rateLimitRedirectsWithRetrySeconds() throws Exception {
        MockHttpServletResponse response = fail(new RateLimitExceededException(93));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=rate&retry=93");
    }

    @Test
    void badCredentialsRedirectToCredentialsCode() throws Exception {
        assertThat(fail(new BadCredentialsException("bad")).getRedirectedUrl()).isEqualTo("/login?error=credentials");
    }

    @Test
    void disabledAccountKeepsItsCode() throws Exception {
        assertThat(fail(new DisabledException("disabled")).getRedirectedUrl()).isEqualTo("/login?error=disabled");
    }

    @Test
    void lockedAccountKeepsItsCode() throws Exception {
        assertThat(fail(new LockedException("locked")).getRedirectedUrl()).isEqualTo("/login?error=locked");
    }

    @Test
    void unknownFailureFallsBackToGenericCode() throws Exception {
        assertThat(fail(new AuthenticationException("other") {}).getRedirectedUrl())
                .isEqualTo("/login?error");
    }

    private MockHttpServletResponse fail(AuthenticationException exception) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request, response, exception);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FOUND);
        return response;
    }
}
