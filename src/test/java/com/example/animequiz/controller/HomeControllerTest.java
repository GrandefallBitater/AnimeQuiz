package com.example.animequiz.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.animequiz.securityConfig.CustomUserDetailsImpl;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HomeControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new homeController()).build();
    }

    @Test
    void showsGuestNameForAnonymousVisitors() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("home"))
                .andExpect(model().attribute("user", "guest"));
    }

    @Test
    void usesCustomNameCookieForAnonymousVisitors() throws Exception {
        mvc.perform(get("/").cookie(new Cookie("CustomUserName", "Bob")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("user", "Bob"));
    }

    @Test
    void showsDisplayNameForAuthenticatedUsers() throws Exception {
        CustomUserDetailsImpl details = new CustomUserDetailsImpl("alice", "hash", "Alice", List.of(), true);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(details, "hash", details.getAuthorities());

        mvc.perform(get("/").principal(auth)).andExpect(status().isOk()).andExpect(model().attribute("user", "Alice"));
    }
}
