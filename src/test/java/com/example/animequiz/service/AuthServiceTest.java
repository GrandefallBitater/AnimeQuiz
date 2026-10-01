package com.example.animequiz.service;

import com.example.animequiz.entity.Authority;
import com.example.animequiz.entity.User;
import com.example.animequiz.securityConfig.CustomUserDetailsImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"SameParameterValue", "Convert2MethodRef"})
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpSession session;

    private AuthService authenticationService;

    @BeforeEach
    void setUp() {
        authenticationService = new AuthService();
        SecurityContextHolder.clearContext();
    }

    private User userWithRole(String username, String displayName, String authority, boolean enabled) {
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPassword("hashed");
        user.setEnabled(enabled);
        user.setAuthorities(new ArrayList<>());
        Authority a = new Authority();
        a.setAuthority(authority);
        a.setUser(user);
        user.getAuthorities().add(a);
        return user;
    }

    @Test
    void refreshAuthenticationBuildsTokenFromUpdatedUser() {
        User updated = userWithRole("john", "Johnny", "ROLE_ADMIN", true);

        CustomUserDetailsImpl oldPrincipal = mock(CustomUserDetailsImpl.class);
        Authentication currentAuth = new UsernamePasswordAuthenticationToken(
                oldPrincipal, "creds", List.of(new SimpleGrantedAuthority("ROLE_USER")));

        UsernamePasswordAuthenticationToken result =
                authenticationService.refreshAuthentication(currentAuth, updated);

        assertThat(result.getPrincipal()).isInstanceOf(CustomUserDetailsImpl.class);
        CustomUserDetailsImpl principal = (CustomUserDetailsImpl) result.getPrincipal();
        assertThat(principal.getUsername()).isEqualTo("john");
        assertThat(principal.getDisplayName()).isEqualTo("Johnny");
        assertThat(principal.isEnabled()).isTrue();
        assertThat(result.getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactly("ROLE_ADMIN");
        assertThat(result.getCredentials()).isEqualTo("creds");
    }

    @Test
    void updateSecurityContextWritesToHttpSession() {
        User updated = userWithRole("john", "Johnny", "ROLE_ADMIN", true);
        Authentication currentAuth = new UsernamePasswordAuthenticationToken(
                "old", "creds", List.of());
        UsernamePasswordAuthenticationToken newAuth =
                authenticationService.refreshAuthentication(currentAuth, updated);

        when(request.getSession(false)).thenReturn(session);

        authenticationService.updateSecurityContext(newAuth, request);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(newAuth);
        verify(session).setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                SecurityContextHolder.getContext()
        );
    }

    @Test
    void updateSecurityContextDoesNotCreateSessionWhenAbsent() {
        User updated = userWithRole("john", "Johnny", "ROLE_ADMIN", true);
        Authentication currentAuth = new UsernamePasswordAuthenticationToken("old", "creds", List.of());
        UsernamePasswordAuthenticationToken newAuth =
                authenticationService.refreshAuthentication(currentAuth, updated);

        when(request.getSession(false)).thenReturn(null);

        authenticationService.updateSecurityContext(newAuth, request);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(newAuth);
        verify(request, never()).getSession(true);
    }

    @Test
    void isAuthenticatedReturnsFalseForNull() {
        assertThat(AuthService.isAuthenticated(null)).isFalse();
    }

    @Test
    void isAuthenticatedReturnsFalseForAnonymous() {
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        assertThat(AuthService.isAuthenticated(anonymous)).isFalse();
    }

    @Test
    void isAuthenticatedReturnsTrueForRealUser() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "john", "creds", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThat(AuthService.isAuthenticated(auth)).isTrue();
    }

    @Test
    void isAdminReturnsTrueForAdminAuthority() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "john", "creds", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThat(AuthService.isAdmin(auth)).isTrue();
    }

    @Test
    void isAdminReturnsFalseForRegularUser() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "john", "creds", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThat(AuthService.isAdmin(auth)).isFalse();
    }
}