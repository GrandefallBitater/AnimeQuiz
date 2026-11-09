package com.example.animequiz.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.animequiz.exceptions.GlobalExceptionHandler;
import com.example.animequiz.exceptions.InvalidInputException;
import com.example.animequiz.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RegisterControllerTest {

    @Mock
    private UserService userService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new RegisterController(userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void registersUserWithNormalizedUsername() throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"  alice  \",\"password\":\"secret1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Аккаунт создан! Теперь войдите"));

        ArgumentCaptor<UserController.UserRequest> captor = ArgumentCaptor.forClass(UserController.UserRequest.class);
        verify(userService).createUser(captor.capture());
        UserController.UserRequest request = captor.getValue();
        assertThat(request.username()).isEqualTo("alice");
        assertThat(request.displayName()).isEqualTo("alice");
        assertThat(request.enabled()).isTrue();
        assertThat(request.role()).isEqualTo("USER");
        assertThat(request.password()).isEqualTo("secret1");
    }

    @Test
    void rejectsInvalidUsernameBeforeCallingService() throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a\",\"password\":\"secret1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Никнейм")));

        verifyNoInteractions(userService);
    }

    @Test
    void returnsServiceValidationMessage() throws Exception {
        doThrow(new InvalidInputException("Пароль должен быть не короче 6 символов"))
                .when(userService)
                .createUser(any());

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Пароль должен быть не короче 6 символов"));
    }
}
