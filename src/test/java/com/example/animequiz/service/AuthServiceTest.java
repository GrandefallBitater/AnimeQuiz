package com.example.animequiz.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.animequiz.entity.Authority;
import com.example.animequiz.entity.User;
import com.example.animequiz.securityConfig.CustomUserDetailsImpl;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AuthServiceTest {

    private final AuthService service = new AuthService();

    @Test
    void refreshAuthenticationBuildsDetailsFromUpdatedUser() {
        Authority authority = new Authority();
        authority.setAuthority("ROLE_ADMIN");

        User user = new User("admin", "new-hash", true);
        user.setDisplayName("Админ");
        user.setAuthorities(new ArrayList<>(List.of(authority)));

        UsernamePasswordAuthenticationToken current = new UsernamePasswordAuthenticationToken("old", "old-pass");

        UsernamePasswordAuthenticationToken refreshed = service.refreshAuthentication(current, user);

        assertThat(refreshed.getPrincipal()).isInstanceOf(CustomUserDetailsImpl.class);
        CustomUserDetailsImpl details = (CustomUserDetailsImpl) refreshed.getPrincipal();
        assertThat(details.getUsername()).isEqualTo("admin");
        assertThat(details.getPassword()).isEqualTo("new-hash");
        assertThat(details.getDisplayName()).isEqualTo("Админ");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
        assertThat(refreshed.getCredentials()).isEqualTo("old-pass");
        assertThat(refreshed.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void isAuthenticatedRejectsNullAndAnonymousAuthentication() {
        assertThat(AuthService.isAuthenticated(null)).isFalse();

        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        assertThat(AuthService.isAuthenticated(anonymous)).isFalse();

        UsernamePasswordAuthenticationToken authenticated =
                new UsernamePasswordAuthenticationToken("alice", "pw", List.of());
        assertThat(AuthService.isAuthenticated(authenticated)).isTrue();
    }

    @Test
    void isAdminChecksTheAdminAuthority() {
        assertThat(AuthService.isAdmin(null)).isFalse();

        UsernamePasswordAuthenticationToken user = new UsernamePasswordAuthenticationToken(
                "alice", "pw", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThat(AuthService.isAdmin(user)).isFalse();

        UsernamePasswordAuthenticationToken admin = new UsernamePasswordAuthenticationToken(
                "admin", "pw", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThat(AuthService.isAdmin(admin)).isTrue();
    }
}
