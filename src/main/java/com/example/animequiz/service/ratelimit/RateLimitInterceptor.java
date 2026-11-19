package com.example.animequiz.service.ratelimit;

import io.github.bucket4j.EstimationProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String POLICY_ATTRIBUTE = RateLimitInterceptor.class.getName() + ".policy";
    private static final String KEY_ATTRIBUTE = RateLimitInterceptor.class.getName() + ".key";

    private final RateLimitService rateLimits;

    public RateLimitInterceptor(RateLimitService rateLimits) {
        this.rateLimits = rateLimits;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Policy policy = policy(request);
        if (policy == null) {
            return true;
        }
        String key = key(policy, request);
        if (key == null) {
            return true;
        }

        EstimationProbe probe = rateLimits.bucket(policy, key).estimateAbilityToConsume(1);
        if (!probe.canBeConsumed()) {
            reject(response, WaitTime.seconds(probe.getNanosToWaitForRefill()));
            return false;
        }

        request.setAttribute(POLICY_ATTRIBUTE, policy);
        request.setAttribute(KEY_ATTRIBUTE, key);
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        if (!(request.getAttribute(POLICY_ATTRIBUTE) instanceof Policy policy)) {
            return;
        }
        if (!(request.getAttribute(KEY_ATTRIBUTE) instanceof String key)) {
            return;
        }
        int status = response.getStatus();
        if (status >= 200 && status < 300) {
            rateLimits.bucket(policy, key).tryConsume(1);
        }
    }

    private static Policy policy(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return switch (path) {
            case "/auth/register" -> Policy.REGISTER_IP;
            case "/profile/import/json" -> Policy.IMPORT_USER;
            default -> null;
        };
    }

    private static String key(Policy policy, HttpServletRequest request) {
        if (policy == Policy.REGISTER_IP) {
            return request.getRemoteAddr();
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }

    private static void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.getWriter()
                .write("{\"message\":\"" + WaitTime.message(retryAfterSeconds) + "\",\"retryAfter\":"
                        + retryAfterSeconds + "}");
    }
}
