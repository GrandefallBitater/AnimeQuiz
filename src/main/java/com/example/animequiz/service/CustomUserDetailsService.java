package com.example.animequiz.service;

import com.example.animequiz.Interfaces.CustomUserDetails;
import com.example.animequiz.entity.Authority;
import com.example.animequiz.entity.User;
import com.example.animequiz.repository.AuthoritiesRepository;
import com.example.animequiz.repository.UserRepository;
import com.example.animequiz.securityConfig.CustomUserDetailsImpl;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final AuthoritiesRepository authoritiesRepository;

    public CustomUserDetailsService(UserRepository userRepository,  AuthoritiesRepository authoritiesRepository) {
        this.userRepository = userRepository;
        this.authoritiesRepository = authoritiesRepository;
    }

    @Override
    public CustomUserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("пользователь не найден: " + username));
        List<Authority> authorities = authoritiesRepository.findByUserUsernameWithUser(user.getUsername());
        List<GrantedAuthority> grantedAuthorities = authorities.stream()
                .map(auth -> new SimpleGrantedAuthority(auth.getAuthority()))
                .collect(Collectors.toList());

        return new CustomUserDetailsImpl(
                user.getUsername(),
                user.getPassword(),
                user.getDisplayName(),
                grantedAuthorities
        );
    }
}
