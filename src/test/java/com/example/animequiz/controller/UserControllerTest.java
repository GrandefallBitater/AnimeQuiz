package com.example.animequiz.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.animequiz.entity.Authority;
import com.example.animequiz.entity.User;
import com.example.animequiz.exceptions.GlobalExceptionHandler;
import com.example.animequiz.service.AuthService;
import com.example.animequiz.service.StorageService;
import com.example.animequiz.service.UserService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private StorageService storageService;

    @Mock
    private AuthService authService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new UserController(userService, storageService, authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getUsersMapsRoleAndAvatarUrl() throws Exception {
        User admin = user("alice", "Alice", "ROLE_ADMIN");
        when(userService.getUsers()).thenReturn(List.of(admin));
        when(storageService.findAvatar("u_alice")).thenReturn(Optional.of(Path.of("avatar.png")));

        mvc.perform(get("/getUsers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[0].displayName").value("Alice"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].role").value("ADMIN"))
                .andExpect(jsonPath("$[0].avatarUrl").value("/profile/avatar?user=alice"));
    }

    @Test
    void getAdminInfoReportsAnonymousUsers() throws Exception {
        mvc.perform(get("/getAdminInfo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void getAdminInfoReturnsCurrentAdminDetails() throws Exception {
        User admin = user("alice", "Alice", "ROLE_ADMIN");
        when(userService.getUsers()).thenReturn(List.of(admin));
        when(storageService.findAvatar("u_alice")).thenReturn(Optional.empty());

        mvc.perform(get("/getAdminInfo").principal(auth("alice", "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.avatarUrl").value("/server-images/anime-logo.png"));
    }

    @Test
    void deleteUserDelegatesToService() throws Exception {
        mvc.perform(delete("/deleteUser")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Пользователь успешно удалён"));

        verify(userService).deleteUser("alice");
    }

    @Test
    void createUserDelegatesToService() throws Exception {
        mvc.perform(post("/createUser")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"secret1\",\"enabled\":true,\"role\":\"USER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Пользователь успешно создан"));

        verify(userService).createUser(any(UserController.UserRequest.class));
    }

    @Test
    void editUserAllowsSelfUpdate() throws Exception {
        User user = user("alice", "Alice", "ROLE_USER");
        UsernamePasswordAuthenticationToken auth = auth("alice", "ROLE_USER");
        UsernamePasswordAuthenticationToken refreshed = auth("alice", "ROLE_USER");
        when(userService.getUser("alice")).thenReturn(Optional.of(user));
        when(authService.refreshAuthentication(any(), any())).thenReturn(refreshed);

        mvc.perform(post("/editUser")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"displayName\":\"Alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSelfUpdate").value(true));

        verify(userService).updateSelfProfile(new UserController.SelfUpdateRequest("alice", "Alice"));
        verify(userService, never()).updateUserByAdmin(any());
        verify(authService).updateSecurityContext(any(), any());
    }

    @Test
    void editUserAllowsAdminToUpdateAnotherUser() throws Exception {
        mvc.perform(
                        post("/editUser")
                                .principal(auth("admin", "ROLE_ADMIN"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"alice\",\"displayName\":\"Alice\",\"enabled\":false,\"role\":\"USER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSelfUpdate").value(false));

        verify(userService).updateUserByAdmin(new UserController.AdminUpdateRequest("alice", "Alice", false, "USER"));
    }

    @Test
    void editUserRejectsOtherUsersForNonAdmins() throws Exception {
        mvc.perform(post("/editUser")
                        .principal(auth("alice", "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"displayName\":\"Bob\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("Доступ")));

        verify(userService, never()).updateSelfProfile(any());
        verify(userService, never()).updateUserByAdmin(any());
    }

    @Test
    void editUserRejectsAnonymousRequests() throws Exception {
        mvc.perform(post("/editUser")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"displayName\":\"Alice\"}"))
                .andExpect(status().isForbidden());
    }

    private static User user(String username, String displayName, String role) {
        User user = new User(username, "hash", true);
        user.setDisplayName(displayName);
        Authority authority = new Authority();
        authority.setAuthority(role);
        authority.setUser(user);
        user.setAuthorities(new ArrayList<>(List.of(authority)));
        return user;
    }

    private static UsernamePasswordAuthenticationToken auth(String name, String role) {
        return new UsernamePasswordAuthenticationToken(name, "pw", List.of(new SimpleGrantedAuthority(role)));
    }
}
