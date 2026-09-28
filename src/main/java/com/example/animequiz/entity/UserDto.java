package com.example.animequiz.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {
    private String username;
    private String displayName;
    private boolean enabled;
    private String role;
    private String avatarUrl;
}