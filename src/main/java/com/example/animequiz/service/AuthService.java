package com.example.animequiz.service;

import com.example.animequiz.entity.User;
import com.example.animequiz.securityConfig.CustomUserDetailsImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    public UsernamePasswordAuthenticationToken refreshAuthentication(Authentication currentAuth, User updatedUser) {
        CustomUserDetailsImpl newDetails = new CustomUserDetailsImpl(
                updatedUser.getUsername(),
                updatedUser.getPassword(),
                updatedUser.getDisplayName(),
                updatedUser.getAuthorities(),
                updatedUser.isEnabled());
        return new UsernamePasswordAuthenticationToken(
                newDetails, currentAuth.getCredentials(), updatedUser.getAuthorities());
    }

    public void updateSecurityContext(UsernamePasswordAuthenticationToken newAuth, HttpServletRequest request) {
        SecurityContext context = SecurityContextHolder.getContext();
        context.setAuthentication(newAuth);

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        }
    }

    public static boolean isAuthenticated(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    public static boolean isAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
