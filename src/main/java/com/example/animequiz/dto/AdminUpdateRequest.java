package com.example.animequiz.dto;

/** Profile fields an administrator may change for any user. */
public record AdminUpdateRequest(String username, String displayName, boolean enabled, String role) {}
