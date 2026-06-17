package org.wildfly.a2a.jakarta.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InternalPathsTest {

    @Test
    void nullValue_returnsEmpty() {
        assertEquals("", InternalPaths.escapeJsonValue(null));
    }

    @Test
    void plainString_isUnchanged() {
        assertEquals("hello world", InternalPaths.escapeJsonValue("hello world"));
    }

    @Test
    void backslash_isEscaped() {
        assertEquals("\\\\", InternalPaths.escapeJsonValue("\\"));
    }

    @Test
    void doubleQuote_isEscaped() {
        assertEquals("\\\"", InternalPaths.escapeJsonValue("\""));
    }

    @Test
    void newline_isEscaped() {
        assertEquals("\\n", InternalPaths.escapeJsonValue("\n"));
    }

    @Test
    void carriageReturn_isEscaped() {
        assertEquals("\\r", InternalPaths.escapeJsonValue("\r"));
    }

    @Test
    void tab_isEscaped() {
        assertEquals("\\t", InternalPaths.escapeJsonValue("\t"));
    }

    @Test
    void controlCharacterNul_isUnicodeEscaped() {
        assertEquals("\\u0000", InternalPaths.escapeJsonValue(String.valueOf((char) 0)));
    }

    @Test
    void controlCharacterSoh_isUnicodeEscaped() {
        assertEquals("\\u0001", InternalPaths.escapeJsonValue(String.valueOf((char) 1)));
    }

    @Test
    void controlCharacterUs_isUnicodeEscaped() {
        assertEquals("\\u001f", InternalPaths.escapeJsonValue(String.valueOf((char) 31)));
    }

    @Test
    void mixedValue_allSpecialsAreEscaped() {
        String input = "a\"b\\c\nd" + (char) 0 + "e";
        assertEquals("a\\\"b\\\\c\\nd\\u0000e", InternalPaths.escapeJsonValue(input));
    }
}
