package com.example.animequiz.securityConfig; // или .security / .domain

import com.example.animequiz.Interfaces.CustomUserDetails;
import org.springframework.security.core.GrantedAuthority;
import java.util.Collection;

public class CustomUserDetailsImpl implements CustomUserDetails {

    private final String username;
    private final String password;
    private final String displayName;
    private final Boolean enabled;
    private final Collection<? extends GrantedAuthority> authorities;

    public CustomUserDetailsImpl(String username, String password,
                                 String displayName, Collection<? extends GrantedAuthority> authorities,
                                 Boolean enabled) {
        this.username = username;
        this.password = password;
        this.displayName = displayName;
        this.authorities = authorities;
        this.enabled = enabled;
    }

    @Override
    public String getDisplayName() {
        return this.displayName;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return this.authorities;
    }

    @Override
    public String getPassword() {
        return this.password;
    }

    @Override
    public String getUsername() {
        return this.username;
    }

    @Override
    public boolean isAccountNonExpired() { return true; }
    @Override
    public boolean isAccountNonLocked() { return true; }
    @Override
    public boolean isCredentialsNonExpired() { return true; }
    @Override
    public boolean isEnabled() { return this.enabled; }
}