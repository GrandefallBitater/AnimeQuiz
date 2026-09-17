package com.example.animequiz.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomUserDetailsServiceTest {

    @Autowired
    CustomUserDetailsService customUserDetailsService;

    @Test
    void UserNotFoundTest() {
        assertThrows(UsernameNotFoundException.class, () -> customUserDetailsService.loadUserByUsername("notfound"));
    }

    @Test
    void loadUserByUsernameOutputNotNull() {
        assertAll(() -> assertNotNull(customUserDetailsService.loadUserByUsername("admin").getPassword()),
                () -> assertNotNull(customUserDetailsService.loadUserByUsername("admin").getAuthorities()),
                () -> assertNotNull(customUserDetailsService.loadUserByUsername("admin").getUsername()));
    }
}