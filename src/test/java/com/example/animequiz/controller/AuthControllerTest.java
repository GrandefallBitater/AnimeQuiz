package com.example.animequiz.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

class AuthControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AuthController())
                .setViewResolvers(new InternalResourceViewResolver("/WEB-INF/views/", ".html"))
                .build();
    }

    @Test
    void loginPageHasNoMessagesByDefault() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attributeDoesNotExist("errorMessage"))
                .andExpect(model().attributeDoesNotExist("infoMessage"));
    }

    @Test
    void loginPageMapsKnownErrorCodes() throws Exception {
        mvc.perform(get("/login").param("error", "credentials"))
                .andExpect(model().attribute("errorMessage", "Неверный никнейм или пароль"));
        mvc.perform(get("/login").param("error", "session"))
                .andExpect(model().attribute("errorMessage", "Сессия истекла. Войдите снова"));
        mvc.perform(get("/login").param("error", "disabled"))
                .andExpect(model().attribute("errorMessage", "Вы были забанены, свяжитесь с администратором"));
        mvc.perform(get("/login").param("error", "something-else"))
                .andExpect(model().attribute("errorMessage", "Ошибка авторизации"));
    }

    @Test
    void loginPageShowsInfoMessage() throws Exception {
        mvc.perform(get("/login").param("message", "Добро пожаловать"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("infoMessage", "Добро пожаловать"));
    }

    @Test
    void registerPageShowsInfoMessage() throws Exception {
        mvc.perform(get("/register").param("message", "Проверьте почту"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(model().attribute("infoMessage", "Проверьте почту"));
    }
}
