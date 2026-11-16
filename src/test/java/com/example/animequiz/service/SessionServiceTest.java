package com.example.animequiz.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.animequiz.securityConfig.CustomUserDetailsImpl;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(30);

    @Mock
    private SessionRegistry sessionRegistry;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(sessionRegistry, SESSION_TIMEOUT);
    }

    @Test
    void expiresOnlySessionsOfMatchingUser() {
        CustomUserDetailsImpl target = details("alice");
        CustomUserDetailsImpl other = details("bob");
        SessionInformation targetSession = mock(SessionInformation.class);

        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(target, other, "raw-principal"));
        when(sessionRegistry.getAllSessions(target, false)).thenReturn(List.of(targetSession));

        sessionService.expireUserSessions("alice");

        verify(targetSession).expireNow();
        verify(sessionRegistry, never()).getAllSessions(other, false);
        verify(sessionRegistry, never()).getAllSessions(eq("raw-principal"), anyBoolean());
    }

    @Test
    void ignoresUsersWithoutSessions() {
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(details("bob")));

        sessionService.expireUserSessions("alice");

        verify(sessionRegistry, never()).getAllSessions(any(), anyBoolean());
    }

    @Test
    void doesNothingWhenNoOneIsLoggedIn() {
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of());

        sessionService.expireUserSessions("alice");

        verify(sessionRegistry, never()).getAllSessions(any(), anyBoolean());
    }

    @Test
    void purgesSessionsIdleLongerThanTimeout() {
        long now = System.currentTimeMillis();
        SessionInformation stale = mock(SessionInformation.class);
        when(stale.getSessionId()).thenReturn("stale");
        when(stale.getLastRequest()).thenReturn(new Date(now - SESSION_TIMEOUT.toMillis() - 60_000));
        SessionInformation fresh = mock(SessionInformation.class);
        when(fresh.getLastRequest()).thenReturn(new Date(now));

        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of("alice"));
        when(sessionRegistry.getAllSessions("alice", true)).thenReturn(List.of(stale, fresh));

        sessionService.purgeIdleSessions();

        verify(sessionRegistry).removeSessionInformation("stale");
        verify(sessionRegistry, never()).removeSessionInformation("fresh");
    }

    private static CustomUserDetailsImpl details(String username) {
        return new CustomUserDetailsImpl(username, "pw", username, List.of(), true);
    }
}
