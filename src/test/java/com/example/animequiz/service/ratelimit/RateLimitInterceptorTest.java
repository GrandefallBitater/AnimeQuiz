package com.example.animequiz.service.ratelimit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class RateLimitInterceptorTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registrationIsRejectedAfterSuccessfulCreation() throws Exception {
        MockMvc mvc = mvc(1, 1);

        mvc.perform(post("/auth/register")).andExpect(status().isOk());
        mvc.perform(post("/auth/register"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.retryAfter").value(3600))
                .andExpect(jsonPath("$.message").value(containsString("Слишком много")));
    }

    @Test
    void failedRegistrationDoesNotSpendBudget() throws Exception {
        MockMvc mvc = mvc(1, 1);

        mvc.perform(post("/auth/register").param("invalid", "true")).andExpect(status().isBadRequest());
        mvc.perform(post("/auth/register")).andExpect(status().isOk());
        mvc.perform(post("/auth/register")).andExpect(status().isTooManyRequests());
    }

    @Test
    void anonymousImportIsNotLimited() throws Exception {
        MockMvc mvc = mvc(1, 1);

        mvc.perform(post("/profile/import/json")).andExpect(status().isAccepted());
        mvc.perform(post("/profile/import/json")).andExpect(status().isAccepted());
    }

    @Test
    void authenticatedImportIsLimitedPerAccount() throws Exception {
        MockMvc mvc = mvc(1, 1);

        authenticate("alice");
        mvc.perform(post("/profile/import/json")).andExpect(status().isAccepted());
        mvc.perform(post("/profile/import/json"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.retryAfter").value(greaterThan(0)));

        authenticate("bob");
        mvc.perform(post("/profile/import/json")).andExpect(status().isAccepted());
    }

    private static void authenticate(String username) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private static MockMvc mvc(int registerCapacity, int importCapacity) {
        RateLimitProperties.Rule login = new RateLimitProperties.Rule(10, Duration.ofMinutes(15));
        RateLimitProperties properties = new RateLimitProperties(
                new RateLimitProperties.Login(login, login),
                new RateLimitProperties.Register(new RateLimitProperties.Rule(registerCapacity, Duration.ofHours(1))),
                new RateLimitProperties.Imports(new RateLimitProperties.Rule(importCapacity, Duration.ofHours(1))));
        RateLimitInterceptor interceptor = new RateLimitInterceptor(new RateLimitService(properties));
        return MockMvcBuilders.standaloneSetup(new StubController())
                .addInterceptors(interceptor)
                .build();
    }

    @RestController
    static class StubController {

        @PostMapping("/auth/register")
        ResponseEntity<Map<String, String>> register(
                @RequestParam(name = "invalid", defaultValue = "false") boolean invalid) {
            if (invalid) {
                return ResponseEntity.badRequest().body(Map.of("message", "bad"));
            }
            return ResponseEntity.ok(Map.of("message", "created"));
        }

        @PostMapping("/profile/import/json")
        ResponseEntity<Map<String, String>> importTitles() {
            return ResponseEntity.accepted().body(Map.of("message", "started"));
        }
    }
}
