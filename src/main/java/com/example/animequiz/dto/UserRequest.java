package com.example.animequiz.dto;

/**
 * Request body for creating, editing and deleting a user.
 */
public record UserRequest(
        String username, String displayName, boolean enabled, String role, String password, String avatarUrl) {}
