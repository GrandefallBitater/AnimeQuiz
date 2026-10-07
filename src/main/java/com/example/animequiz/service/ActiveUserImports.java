package com.example.animequiz.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class ActiveUserImports {

    private final ConcurrentHashMap<String, Long> active = new ConcurrentHashMap<>();

    public void start(String username) {
        active.put(username, System.currentTimeMillis());
    }

    public void finish(String username) {
        active.remove(username);
    }

    public boolean isRunning(String username) {
        return active.containsKey(username);
    }
}