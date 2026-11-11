package com.example.animequiz.service;

import com.example.animequiz.securityConfig.CustomUserDetailsImpl;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

@Service
public class SessionService {

    private final SessionRegistry sessionRegistry;
    private final Duration sessionTimeout;

    @Autowired
    public SessionService(
            SessionRegistry sessionRegistry, @Value("${server.servlet.session.timeout:30m}") Duration sessionTimeout) {
        this.sessionRegistry = sessionRegistry;
        this.sessionTimeout = sessionTimeout;
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

    @Scheduled(fixedDelayString = "PT10M")
    void purgeIdleSessions() {
        long cutoff = System.currentTimeMillis() - sessionTimeout.toMillis();
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            for (SessionInformation session : sessionRegistry.getAllSessions(principal, true)) {
                Date lastRequest = session.getLastRequest();
                if (lastRequest != null && lastRequest.getTime() < cutoff) {
                    sessionRegistry.removeSessionInformation(session.getSessionId());
                }
            }
        }
    }
}
