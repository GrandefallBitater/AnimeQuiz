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
}
