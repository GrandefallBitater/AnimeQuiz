package com.example.animequiz.dto;

/** Profile fields a user may change on their own page. */
public record SelfUpdateRequest(String username, String displayName) {}
