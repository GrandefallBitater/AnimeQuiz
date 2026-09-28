package com.example.animequiz.service;

import com.example.animequiz.Interfaces.CustomUserDetails;
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
        CustomUserDetails details = customUserDetailsService.loadUserByUsername("admin");
        assertAll(
                () -> assertNotNull(details.getPassword(), "Пароль не должен быть null"),
                () -> assertNotNull(details.getAuthorities(), "Роли не должны быть null"),
                () -> assertNotNull(details.getUsername(), "Имя пользователя не должно быть null"),
                () -> assertNotNull(details.getDisplayName(), "Отображаемое имя не должно быть null")
        );
    }
}