package com.example.animequiz.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.animequiz.exceptions.InvalidInputException;
import org.junit.jupiter.api.Test;

class InputValidationTest {

    @Test
    void normalizeUsernameTrimsSurroundingSpaces() {
        assertEquals("ruslan", InputValidation.normalizeUsername("  ruslan  "));
    }

    @Test
    void normalizeUsernameRejectsEmptyValue() {
        assertThrows(InvalidInputException.class, () -> InputValidation.normalizeUsername("   "));
        assertThrows(InvalidInputException.class, () -> InputValidation.normalizeUsername(null));
    }

    @Test
    void normalizeUsernameRejectsSpacesInsideTheName() {
        assertThrows(InvalidInputException.class, () -> InputValidation.normalizeUsername("bad name"));
    }

    @Test
    void normalizeUsernameRejectsTooShortValue() {
        assertThrows(InvalidInputException.class, () -> InputValidation.normalizeUsername("a"));
    }

    @Test
    void normalizeDisplayNameRejectsMarkup() {
        assertThrows(InvalidInputException.class, () -> InputValidation.normalizeDisplayName("<b>name</b>"));
    }

    @Test
    void normalizeDisplayNameKeepsNormalText() {
        assertEquals("Руслан", InputValidation.normalizeDisplayName("  Руслан  "));
    }

    @Test
    void forLogReplacesControlCharactersAndTruncates() {
        assertEquals("a_b", InputValidation.forLog("a\tb"));
        assertEquals(
                128,
                InputValidation.forLog(new String(new char[200]).replace('\0', 'x'))
                        .length());
    }

    @Test
    void normalizeUsernameAcceptsAllowedCharacters() {
        assertEquals("Руслан_01.a-b", InputValidation.normalizeUsername("Руслан_01.a-b"));
    }

    @Test
    void normalizeUsernameAcceptsBoundaryLengths() {
        assertEquals("ab", InputValidation.normalizeUsername("ab"));
        assertEquals("a".repeat(64), InputValidation.normalizeUsername("a".repeat(64)));

        assertThrows(InvalidInputException.class, () -> InputValidation.normalizeUsername("a".repeat(65)));
    }

    @Test
    void normalizeDisplayNameRejectsBlankAndTooLongValues() {
        assertThrows(InvalidInputException.class, () -> InputValidation.normalizeDisplayName("   "));
        assertThrows(InvalidInputException.class, () -> InputValidation.normalizeDisplayName("a".repeat(65)));
    }

    @Test
    void normalizeDisplayNameCountsCodePoints() {
        String emoji = "😀".repeat(64);

        assertEquals(emoji, InputValidation.normalizeDisplayName(emoji));
        assertThrows(InvalidInputException.class, () -> InputValidation.normalizeDisplayName("😀".repeat(65)));
    }

    @Test
    void normalizeDisplayNameRejectsControlCharacters() {
        assertThrows(InvalidInputException.class, () -> InputValidation.normalizeDisplayName("bad\nname"));
    }

    @Test
    void normalizeLobbyNameTrimsAndRejectsMarkup() {
        assertEquals("Лобби", InputValidation.normalizeLobbyName("  Лобби  "));
        assertThrows(InvalidInputException.class, () -> InputValidation.normalizeLobbyName("<b>lobby</b>"));
    }

    @Test
    void forLogHandlesNullAndKeepsShortValues() {
        assertEquals("", InputValidation.forLog(null));
        assertEquals("abc", InputValidation.forLog("abc"));
        assertEquals("a_b_c", InputValidation.forLog("a\nb\tc"));
    }
}
