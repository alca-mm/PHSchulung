package de.internal.awareness.file;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Erzeugung sicherer Dateinamen fuer die Dateibibliothek.
 *
 * <p>Zwei getrennte Namen (siehe {@link GeneratedFile}):</p>
 * <ul>
 *   <li>Der PHYSISCHE Dateiname ({@link #newStoredFilename}) wird intern per UUID vergeben und enthaelt nie
 *       Benutzereingaben - so ist Path-Traversal auf Dateisystemebene ausgeschlossen.</li>
 *   <li>Der DOWNLOAD-Name ({@link #safeDownloadFilename}) ist benutzerfreundlich, wird aber streng bereinigt:
 *       Pfadangaben, Traversal und gefaehrliche Zeichen werden ABGELEHNT bzw. entfernt, die korrekte
 *       Endung wird anhand des Typs ergaenzt (keine vom Benutzer angegebene Doppelendung).</li>
 * </ul>
 */
public final class SafeFileNames {

    /** Maximale Laenge des bereinigten Basisnamens (ohne Endung). */
    private static final int MAX_BASE_LENGTH = 100;

    /** Erlaubte Zeichen im Basisnamen: Buchstaben (ASCII), Ziffern, Bindestrich, Unterstrich, Punkt. */
    private static final Pattern DISALLOWED = Pattern.compile("[^A-Za-z0-9._-]");

    /** Eine einzelne, am Ende stehende Endung (Punkt + 1-8 alphanumerische Zeichen). */
    private static final Pattern TRAILING_EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,8}$");

    private SafeFileNames() {
    }

    /** Intern vergebener physischer Dateiname: UUID + korrekte Endung. Enthaelt nie Benutzereingaben. */
    public static String newStoredFilename(GeneratedFileType type) {
        return UUID.randomUUID() + "." + type.extension();
    }

    /**
     * Baut aus dem vom Benutzer gewuenschten Basisnamen einen sicheren Download-Dateinamen mit der zum Typ
     * passenden Endung.
     *
     * <p><b>Abgelehnt</b> (mit {@link IllegalArgumentException}) werden Eingaben, die auf einen Ausbruch aus
     * dem Verzeichnis hindeuten oder sonst unsicher sind: {@code null}/leer, Null-Bytes, Steuerzeichen,
     * Pfadtrenner ({@code /} oder {@code \}), Traversal ({@code ..}), absolute Pfade (fuehrender Slash oder
     * Laufwerksbuchstabe wie {@code C:}), sowie Eingaben, die nach der Bereinigung leer waeren.</p>
     *
     * <p>Andernfalls werden verbleibende Sonderzeichen durch {@code -} ersetzt, eine bereits vorhandene
     * Endung entfernt (keine Doppelendung), die Laenge begrenzt und die korrekte Endung angehaengt.</p>
     */
    public static String safeDownloadFilename(String rawBaseName, GeneratedFileType type) {
        if (type == null) {
            throw new IllegalArgumentException("Dateityp fehlt.");
        }
        if (rawBaseName == null || rawBaseName.isBlank()) {
            throw new IllegalArgumentException("Dateiname fehlt.");
        }
        String name = rawBaseName.trim();

        // Harte Ablehnung eindeutig unsicherer Eingaben (kein stilles Bereinigen von Traversal/Pfaden).
        if (name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Dateiname enthält ein Null-Byte.");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IllegalArgumentException("Dateiname enthält Steuerzeichen.");
            }
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Dateiname darf keine Pfadtrenner enthalten.");
        }
        if (name.contains("..")) {
            throw new IllegalArgumentException("Dateiname darf kein Verzeichnis-Traversal (..) enthalten.");
        }
        // Absoluter Windows-Pfad (z. B. "C:name") - Slashes sind oben bereits ausgeschlossen.
        if (name.length() >= 2 && Character.isLetter(name.charAt(0)) && name.charAt(1) == ':') {
            throw new IllegalArgumentException("Absolute Pfade sind nicht erlaubt.");
        }

        // Eine vom Benutzer angegebene Endung entfernen (verhindert z. B. "name.docx" -> "name.docx.docx").
        String base = TRAILING_EXTENSION.matcher(name).replaceFirst("");

        // Verbleibende unerlaubte Zeichen durch '-' ersetzen und Mehrfach-Trenner zusammenfassen.
        base = DISALLOWED.matcher(base).replaceAll("-");
        base = base.replaceAll("-{2,}", "-");
        // Fuehrende/abschliessende Trenner und Punkte entfernen.
        base = base.replaceAll("^[-.]+", "").replaceAll("[-.]+$", "");

        if (base.isEmpty()) {
            throw new IllegalArgumentException("Dateiname ist nach der Bereinigung leer.");
        }
        if (base.length() > MAX_BASE_LENGTH) {
            base = base.substring(0, MAX_BASE_LENGTH);
            base = base.replaceAll("[-.]+$", "");
        }
        if (base.isEmpty()) {
            throw new IllegalArgumentException("Dateiname ist nach der Bereinigung leer.");
        }
        return base + "." + type.extension().toLowerCase(Locale.ROOT);
    }
}
