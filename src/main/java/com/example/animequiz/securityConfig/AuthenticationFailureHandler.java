package com.example.animequiz.securityConfig;

import com.example.animequiz.service.ratelimit.RateLimitExceededException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class AuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, ServletException {
        saveException(request, exception);
        getRedirectStrategy().sendRedirect(request, response, failureUrl(exception));
    }

    private static String failureUrl(AuthenticationException exception) {
        if (exception instanceof RateLimitExceededException rateLimit) {
            return "/login?error=rate&retry=" + rateLimit.getRetryAfterSeconds();
        }
        if (exception instanceof DisabledException) {
            return "/login?error=disabled";
        }
        if (exception instanceof LockedException) {
            return "/login?error=locked";
        }
        if (exception instanceof BadCredentialsException) {
            return "/login?error=credentials";
        }
        return "/login?error";
    }
}
