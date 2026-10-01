package com.example.animequiz.service;

import com.example.animequiz.securityConfig.CustomUserDetailsImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SessionService {

    private final SessionRegistry sessionRegistry;

    @Autowired
    public SessionService(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    public void expireUserSessions(String username) {
        List<Object> allPrincipals = sessionRegistry.getAllPrincipals();

        for (Object principal : allPrincipals) {
            if (principal instanceof CustomUserDetailsImpl userDetails) {
                if (userDetails.getUsername().equals(username)) {
                    List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
                    for (SessionInformation session : sessions) {
                        session.expireNow();
                    }
                }
            }
        }
    }
}
