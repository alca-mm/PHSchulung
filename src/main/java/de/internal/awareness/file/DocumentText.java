package de.internal.awareness.file;

/**
 * Kleine Text-Hilfsfunktionen fuer die Dateierzeugung.
 *
 * <p>Insbesondere entfernt {@link #stripXmlIncompatibleChars(String)} Zeichen, die in XML 1.0 nicht erlaubt
 * sind (z. B. die meisten Steuerzeichen). Sowohl das erzeugte XML als auch das OOXML-basierte DOCX sind
 * XML-Dokumente; solche Zeichen wuerden zu einem NICHT wohlgeformten bzw. nicht oeffenbaren Dokument
 * fuehren. Erhalten bleiben ausschliesslich Tab ({@code \t}), Zeilenumbruch ({@code \n}) und
 * Wagenruecklauf ({@code \r}).</p>
 */
public final class DocumentText {

    private DocumentText() {
    }

    /**
     * Entfernt alle in XML 1.0 unzulaessigen Zeichen aus dem Text (behaelt {@code \t}, {@code \n},
     * {@code \r}). {@code null} bleibt {@code null}.
     */
    public static String stripXmlIncompatibleChars(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(input.length());
        input.codePoints().forEach(codePoint -> {
            if (isXmlChar(codePoint)) {
                sb.appendCodePoint(codePoint);
            }
        });
        return sb.toString();
    }

    /** Gueltiger XML-1.0-Zeichenbereich (ohne die verbotenen Steuer- und Nichtzeichen). */
    private static boolean isXmlChar(int c) {
        return c == 0x9 || c == 0xA || c == 0xD
                || (c >= 0x20 && c <= 0xD7FF)
                || (c >= 0xE000 && c <= 0xFFFD)
                || (c >= 0x10000 && c <= 0x10FFFF);
    }
}
