package com.example.animequiz.Interfaces;

import org.springframework.security.core.userdetails.UserDetails;

public interface CustomUserDetails extends UserDetails {
    String getDisplayName();
}
