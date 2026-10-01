package com.example.animequiz.service;

import com.example.animequiz.securityConfig.CustomUserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private SessionRegistry sessionRegistry;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(sessionRegistry);
    }

    @Test
    void expireUserSessionsExpiresAllSessionsOfTargetUser() {
        CustomUserDetailsImpl john = mock(CustomUserDetailsImpl.class);
        when(john.getUsername()).thenReturn("john");

        SessionInformation session1 = mock(SessionInformation.class);
        SessionInformation session2 = mock(SessionInformation.class);

        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(john));
        when(sessionRegistry.getAllSessions(john, false)).thenReturn(List.of(session1, session2));

        sessionService.expireUserSessions("john");

        verify(session1).expireNow();
        verify(session2).expireNow();
    }

    @Test
    void expireUserSessionsDoesNotTouchOtherUsers() {
        CustomUserDetailsImpl john = mock(CustomUserDetailsImpl.class);
        when(john.getUsername()).thenReturn("john");

        CustomUserDetailsImpl alice = mock(CustomUserDetailsImpl.class);
        when(alice.getUsername()).thenReturn("alice");

        SessionInformation johnSession = mock(SessionInformation.class);

        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(john, alice));
        when(sessionRegistry.getAllSessions(john, false)).thenReturn(List.of(johnSession));

        sessionService.expireUserSessions("john");

        verify(johnSession).expireNow();
        verify(sessionRegistry, never()).getAllSessions(eq(alice), anyBoolean());
    }

    @Test
    void expireUserSessionsDoesNothingWhenUserHasNoSessions() {
        CustomUserDetailsImpl alice = mock(CustomUserDetailsImpl.class);
        when(alice.getUsername()).thenReturn("alice");

        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(alice));

        sessionService.expireUserSessions("john");

        verify(sessionRegistry, never()).getAllSessions(any(), anyBoolean());
    }

    @Test
    void expireUserSessionsIgnoresNonCustomPrincipals() {
        Object stranger = new Object();

        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(stranger));

        sessionService.expireUserSessions("john");

        verify(sessionRegistry, never()).getAllSessions(any(), anyBoolean());
    }

    @Test
    void expireUserSessionsHandlesMultiplePrincipalsWithSameUsername() {
        CustomUserDetailsImpl john1 = mock(CustomUserDetailsImpl.class);
        when(john1.getUsername()).thenReturn("john");

        CustomUserDetailsImpl john2 = mock(CustomUserDetailsImpl.class);
        when(john2.getUsername()).thenReturn("john");

        SessionInformation s1 = mock(SessionInformation.class);
        SessionInformation s2 = mock(SessionInformation.class);

        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(john1, john2));
        when(sessionRegistry.getAllSessions(john1, false)).thenReturn(List.of(s1));
        when(sessionRegistry.getAllSessions(john2, false)).thenReturn(List.of(s2));

        sessionService.expireUserSessions("john");

        verify(s1).expireNow();
        verify(s2).expireNow();
        verify(sessionRegistry, times(2)).getAllSessions(any(), eq(false));
    }
}