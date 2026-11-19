package com.example.animequiz.service.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rate.limit")
public record RateLimitProperties(Login login, Register register, Imports imports) {

    public RateLimitProperties {
        require(login == null ? null : login.username(), "login.username");
        require(login == null ? null : login.ip(), "login.ip");
        require(register == null ? null : register.ip(), "register.ip");
        require(imports == null ? null : imports.user(), "imports.user");
    }

    private static void require(Rule rule, String path) {
        if (rule == null) {
            throw new IllegalArgumentException("Не задано правило rate.limit." + path);
        }
    }

    public record Login(Rule username, Rule ip) {}

    public record Register(Rule ip) {}

    public record Imports(Rule user) {}

    public record Rule(int capacity, Duration window) {

        public Rule {
            if (capacity < 1) {
                throw new IllegalArgumentException("Ёмкость правила должна быть больше нуля");
            }
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("Окно правила должно быть положительным");
            }
        }
    }
}
