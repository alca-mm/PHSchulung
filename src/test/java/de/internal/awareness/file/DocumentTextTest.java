package de.internal.awareness.file;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entfernung XML-1.0-unzulaessiger Steuerzeichen: verbotene Steuerzeichen werden entfernt, erlaubte
 * Whitespaces ({@code \t}, {@code \n}, {@code \r}) und normale (auch nicht-ASCII-)Zeichen bleiben erhalten.
 */
class DocumentTextTest {

    @Test
    void removesForbiddenControlCharacters() {
        assertThat(DocumentText.stripXmlIncompatibleChars("a\u0000b\u0008c\u001Fd")).isEqualTo("abcd");
        assertThat(DocumentText.stripXmlIncompatibleChars("x\u000By\u000Cz")).isEqualTo("xyz");
    }

    @Test
    void keepsAllowedWhitespaceAndRegularCharacters() {
        assertThat(DocumentText.stripXmlIncompatibleChars("a\tb\nc\rd")).isEqualTo("a\tb\nc\rd");
        // Nicht-ASCII (Umlaute) bleiben erhalten.
        assertThat(DocumentText.stripXmlIncompatibleChars("Grüße 123")).isEqualTo("Grüße 123");
    }

    @Test
    void nullStaysNull() {
        assertThat(DocumentText.stripXmlIncompatibleChars(null)).isNull();
    }
}
