package com.example.animequiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.animequiz.Interfaces.CustomUserDetails;
import com.example.animequiz.entity.Authority;
import com.example.animequiz.entity.User;
import com.example.animequiz.repository.AuthoritiesRepository;
import com.example.animequiz.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthoritiesRepository authoritiesRepository;

    @InjectMocks
    private CustomUserDetailsService service;

    @Test
    void loadsUserWithAuthorities() {
        User user = new User("alice", "hash", true);
        user.setDisplayName("Alice");

        Authority authority = new Authority();
        authority.setAuthority("ROLE_USER");

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(authoritiesRepository.findByUserUsernameWithUser("alice")).thenReturn(List.of(authority));

        CustomUserDetails details = service.loadUserByUsername("alice");

        assertThat(details.getUsername()).isEqualTo("alice");
        assertThat(details.getPassword()).isEqualTo("hash");
        assertThat(details.getDisplayName()).isEqualTo("Alice");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void throwsWhenUserDoesNotExist() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("ghost");

        verifyNoInteractions(authoritiesRepository);
    }
}
