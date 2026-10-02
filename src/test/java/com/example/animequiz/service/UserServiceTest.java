package com.example.animequiz.service;

import com.example.animequiz.controller.UserController;
import com.example.animequiz.entity.Authority;
import com.example.animequiz.entity.User;
import com.example.animequiz.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("SameParameterValue")
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private StorageService storageService;
    @Mock
    private SessionService sessionService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, storageService, sessionService);
    }

    private UserController.UserRequest request(String username, String displayName,
                                               String password, String role, boolean enabled) {
        return new UserController.UserRequest(username, displayName, enabled, role, password, null);
    }

    private User userWithRole(String username, String authority) {
        User user = new User();
        user.setUsername(username);
        user.setAuthorities(new ArrayList<>());
        Authority auth = new Authority();
        auth.setAuthority(authority);
        auth.setUser(user);
        user.getAuthorities().add(auth);
        return user;
    }

    @Test
    void getUsersReturnsAllUsers() {
        User user = new User();
        when(userRepository.findAll()).thenReturn(List.of(user));

        assertThat(userService.getUsers()).containsExactly(user);
    }

    @Test
    void getUserReturnsUserWhenPresent() {
        User user = new User();
        when(userRepository.findById("john")).thenReturn(Optional.of(user));

        assertThat(userService.getUser("john")).contains(user);
    }

    @Test
    void getUserReturnsEmptyOnException() {
        when(userRepository.findById("bad")).thenThrow(new RuntimeException("db down"));

        assertThat(userService.getUser("bad")).isEmpty();
    }

    @Test
    void createUserEncodesPasswordAndAssignsRole() {
        when(passwordEncoder.encode("secret")).thenReturn("hashed");

        String message = userService.createUser(request("john", "John", "secret", "admin", true));

        assertThat(message).contains("успешно");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        User saved = captor.getValue();
        assertThat(saved.getUsername()).isEqualTo("john");
        assertThat(saved.getDisplayName()).isEqualTo("John");
        assertThat(saved.getPassword()).isEqualTo("hashed");
        assertThat(saved.getAuthorities())
                .extracting(Authority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void createUserDefaultsToRoleUserWhenRoleIsNull() {
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        userService.createUser(request("john", "John", "p", null, true));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getAuthorities())
                .extracting(Authority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void createUserReturnsErrorWhenSaveFails() {
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenThrow(new RuntimeException("duplicate key"));

        String message = userService.createUser(request("john", "John", "p", "USER", true));

        assertThat(message).contains("что-то пошло не так");
    }

    @Test
    void updateUserChangesFieldsAndKeepsSessionWhenRoleUnchanged() {
        User existing = userWithRole("john", "ROLE_USER");
        when(userRepository.findById("john")).thenReturn(Optional.of(existing));

        String message = userService.updateUser(request("john", "New Name", null, "USER", false), true);

        assertThat(message).contains("обновлён");
        assertThat(existing.getDisplayName()).isEqualTo("New Name");
        assertThat(existing.isEnabled()).isFalse();
        assertThat(existing.getAuthorities())
                .extracting(Authority::getAuthority)
                .containsExactly("ROLE_USER");
        verifyNoInteractions(sessionService);
    }

    @Test
    void updateUserChangesRoleAndExpiresSessions() {
        User existing = userWithRole("john", "ROLE_USER");
        when(userRepository.findById("john")).thenReturn(Optional.of(existing));

        userService.updateUser(request("john", "John", null, "ADMIN", true), false);

        assertThat(existing.getAuthorities())
                .extracting(Authority::getAuthority)
                .containsExactly("ROLE_ADMIN");
        verify(sessionService).expireUserSessions("john");
    }

    @Test
    void updateUserDoesNotExpireSessionsOfOtherUsers() {
        User existing = userWithRole("john", "ROLE_USER");
        when(userRepository.findById("john")).thenReturn(Optional.of(existing));
        userService.updateUser(request("john", "John", null, "USER", true), true);

        verify(sessionService, never()).expireUserSessions(anyString());
    }

    @Test
    void updateUserThrowsWhenUserNotFound() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUser(request("ghost", "G", null, "USER", true), false))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void deleteUserRemovesEntityAndAvatar() throws Exception {
        String message = userService.deleteUser("john");

        assertThat(message).contains("успешно");
        verify(userRepository).deleteById("john");
        verify(storageService).deleteAvatar("u_john");
    }

    @Test
    void deleteUserReturnsErrorMessageOnFailure() {
        doThrow(new RuntimeException("db down")).when(userRepository).deleteById("john");

        String message = userService.deleteUser("john");

        assertThat(message).contains("что-то пошло не так");
    }
}