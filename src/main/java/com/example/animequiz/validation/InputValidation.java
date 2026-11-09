package com.example.animequiz.validation;

import com.example.animequiz.exceptions.InvalidInputException;
import java.util.regex.Pattern;

public final class InputValidation {

    public static final int USERNAME_MIN = 2;
    public static final int USERNAME_MAX = 64;
    public static final int DISPLAY_NAME_MAX = 64;
    public static final int LOBBY_NAME_MAX = 64;

    private static final Pattern USERNAME =
            Pattern.compile("^[\\p{L}\\p{N}_.-]{" + USERNAME_MIN + "," + USERNAME_MAX + "}$");
    private static final Pattern FORBIDDEN_DISPLAY_CHARS = Pattern.compile("[<>\\p{Cntrl}]");
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    private InputValidation() {}

    public static String normalizeUsername(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new InvalidInputException("Никнейм не может быть пустым");
        }
        if (!USERNAME.matcher(value).matches()) {
            throw new InvalidInputException("Никнейм: от " + USERNAME_MIN + " до " + USERNAME_MAX
                    + " символов, только буквы, цифры, точка, дефис и подчёркивание");
        }
        return value;
    }

    public static String normalizeDisplayName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new InvalidInputException("Имя не может быть пустым");
        }
        if (value.codePointCount(0, value.length()) > DISPLAY_NAME_MAX) {
            throw new InvalidInputException("Имя не должно превышать " + DISPLAY_NAME_MAX + " символов");
        }
        if (FORBIDDEN_DISPLAY_CHARS.matcher(value).find()) {
            throw new InvalidInputException("Имя содержит недопустимые символы");
        }
        return value;
    }

    public static String normalizeLobbyName(String raw) {
        String value = normalizeDisplayName(raw);
        if (value.codePointCount(0, value.length()) > LOBBY_NAME_MAX) {
            throw new InvalidInputException("Имя не должно превышать " + LOBBY_NAME_MAX + " символов");
        }
        return value;
    }

    public static String forLog(String raw) {
        if (raw == null) {
            return "";
        }
        String value = CONTROL_CHARS.matcher(raw).replaceAll("_");
        return value.length() > 128 ? value.substring(0, 128) : value;
    }
}
