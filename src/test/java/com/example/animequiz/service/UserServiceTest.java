package com.example.animequiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.animequiz.dto.AdminUpdateRequest;
import com.example.animequiz.dto.SelfUpdateRequest;
import com.example.animequiz.dto.UserRequest;
import com.example.animequiz.entity.Authority;
import com.example.animequiz.entity.User;
import com.example.animequiz.exceptions.ConflictException;
import com.example.animequiz.exceptions.InvalidInputException;
import com.example.animequiz.exceptions.NotFoundException;
import com.example.animequiz.repository.UserRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

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

    @InjectMocks
    private UserService userService;

    @Test
    void createUserNormalizesInputAndStoresHashedPassword() {
        when(passwordEncoder.encode("secret1")).thenReturn("hashed");

        userService.createUser(request("  alice  ", "  Alice  ", null, "secret1"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getDisplayName()).isEqualTo("Alice");
        assertThat(saved.getPassword()).isEqualTo("hashed");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getAuthorities()).hasSize(1);
        assertThat(saved.getAuthorities().get(0).getAuthority()).isEqualTo("ROLE_USER");
        assertThat(saved.getAuthorities().get(0).getUser()).isSameAs(saved);
    }

    @Test
    void createUserUsesUsernameWhenDisplayNameIsBlank() {
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        userService.createUser(request("alice", "   ", null, "secret1"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getDisplayName()).isEqualTo("alice");
    }

    @Test
    void createUserAcceptsAdminRoleInAnyCaseAndWithPrefix() {
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        userService.createUser(request("alice", null, "role_admin", "secret1"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getAuthorities().get(0).getAuthority()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void createUserFallsBackToUserRoleForUnknownRole() {
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        userService.createUser(request("alice", null, "superuser", "secret1"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getAuthorities().get(0).getAuthority()).isEqualTo("ROLE_USER");
    }

    @Test
    void createUserRejectsShortAndMissingPassword() {
        assertThatThrownBy(() -> userService.createUser(request("alice", null, null, "12345")))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("6");

        assertThatThrownBy(() -> userService.createUser(request("alice", null, null, null)))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("6");

        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void createUserReportsDuplicateUsernameAsConflict() {
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> userService.createUser(request("alice", null, null, "secret1")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Никнейм уже занят");
    }

    @Test
    void deleteUserRemovesUserSessionsAndAvatar() throws IOException {
        User user = new User("alice", "pw", true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        userService.deleteUser("alice");

        verify(userRepository).deleteById("alice");
        verify(sessionService).expireUserSessions("alice");
        verify(storageService).deleteAvatar("u_alice");
    }

    @Test
    void deleteUserIgnoresAvatarDeletionFailures() throws IOException {
        User user = new User("alice", "pw", true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        doThrow(new IOException("disk")).when(storageService).deleteAvatar("u_alice");

        assertThatCode(() -> userService.deleteUser("alice")).doesNotThrowAnyException();
    }

    @Test
    void deleteUserThrowsWhenUserDoesNotExist() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteUser("ghost"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Пользователь не найден");

        verify(userRepository, never()).deleteById(anyString());
        verifyNoInteractions(sessionService, storageService);
    }

    @Test
    void updateSelfProfileChangesDisplayName() {
        User user = new User("alice", "pw", true);
        when(userRepository.findById("alice")).thenReturn(Optional.of(user));

        userService.updateSelfProfile(new SelfUpdateRequest("alice", "  Alice  "));

        assertThat(user.getDisplayName()).isEqualTo("Alice");
    }

    @Test
    void updateSelfProfileThrowsWhenUserDoesNotExist() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateSelfProfile(new SelfUpdateRequest("ghost", "Ghost")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateUserByAdminReplacesRoleAndExpiresSessions() {
        User user = new User("alice", "pw", true);
        Authority oldRole = new Authority();
        oldRole.setAuthority("ROLE_USER");
        oldRole.setUser(user);
        user.setAuthorities(new ArrayList<>(List.of(oldRole)));
        when(userRepository.findById("alice")).thenReturn(Optional.of(user));

        userService.updateUserByAdmin(new AdminUpdateRequest("alice", "Alice", false, "ADMIN"));

        assertThat(user.getDisplayName()).isEqualTo("Alice");
        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getAuthorities()).hasSize(1);
        assertThat(user.getAuthorities().get(0).getAuthority()).isEqualTo("ROLE_ADMIN");
        verify(sessionService).expireUserSessions("alice");
    }

    @Test
    void updateUserByAdminKeepsExistingRoleWhenItMatches() {
        User user = new User("alice", "pw", true);
        Authority role = new Authority();
        role.setAuthority("ROLE_USER");
        role.setUser(user);
        user.setAuthorities(new ArrayList<>(List.of(role)));
        when(userRepository.findById("alice")).thenReturn(Optional.of(user));

        userService.updateUserByAdmin(new AdminUpdateRequest("alice", "Alice", true, "USER"));

        assertThat(user.getAuthorities()).containsExactly(role);
    }

    @Test
    void updateUserByAdminThrowsWhenUserDoesNotExist() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUserByAdmin(new AdminUpdateRequest("ghost", "Ghost", true, "USER")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void readsUsersAndSingleUser() {
        User user = new User("alice", "pw", true);
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(userRepository.findById("alice")).thenReturn(Optional.of(user));

        assertThat(userService.getUsers()).containsExactly(user);
        assertThat(userService.getUser("alice")).contains(user);
    }

    private static UserRequest request(String username, String displayName, String role, String password) {
        return new UserRequest(username, displayName, true, role, password, null);
    }
}
