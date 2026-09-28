package de.internal.awareness.file;

import java.util.Locale;

/**
 * Erlaubte Typen erzeugbarer, PASSIVER Trainingsdateien.
 *
 * <p>Bewusst nur ein kleiner, fester Katalog ungefaehrlicher Dokumentformate - kein freies Dateiformat.
 * Nicht erlaubt sind insbesondere makrofaehige oder ausfuehrbare Formate (z. B. {@code .docm}, VBA,
 * eingebettete Objekte). Jeder Typ traegt seine Standard-Dateiendung und den zugehoerigen Content-Type.</p>
 *
 * <p>Die Konstanten muessen exakt der CHECK-Constraint der Spalte {@code file_type} entsprechen
 * (Flyway V5, erweitert in V10); ein neuer Wert erfordert eine neue Migration. Zusaetzlich braucht jeder Typ
 * genau einen {@link FileContentGenerator} - {@link GeneratedFileService} prueft das beim Start (fail-fast) und
 * erzeugt nie still ein anderes Format.</p>
 */
public enum GeneratedFileType {

    /** Word-Dokument im Office-Open-XML-Format (passiv, ohne Makros). */
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),

    /** Selbstenthaltende XML-Datei (UTF-8, ohne DTD/DOCTYPE/externe Entities). */
    XML("xml", "application/xml"),

    /** PDF-Dokument (passiv: ohne JavaScript, ohne OpenAction/Launch, ohne eingebettete Dateien). */
    PDF("pdf", "application/pdf"),

    /** Excel-Arbeitsmappe im Office-Open-XML-Format (passiv: ohne Makros, ohne externe Links/Datenverbindungen). */
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),

    /** PowerPoint-Praesentation im Office-Open-XML-Format (passiv: ohne Makros, ohne OLE/externe Ressourcen). */
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),

    /** UTF-8-Textdatei (reiner Text, keine ausfuehrbaren Inhalte). */
    TXT("txt", "text/plain;charset=UTF-8"),

    /** UTF-8-CSV-Datei (mit Schutz gegen Formula-Injection, siehe {@link CsvSanitizer}). */
    CSV("csv", "text/csv;charset=UTF-8");

    private final String extension;
    private final String contentType;

    GeneratedFileType(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    /** Standard-Dateiendung ohne fuehrenden Punkt (z. B. {@code "docx"}). */
    public String extension() {
        return extension;
    }

    /** Zugehoeriger MIME-Content-Type fuer Download/Anhang. */
    public String contentType() {
        return contentType;
    }

    /**
     * Bildet einen (auch klein/gemischt geschriebenen) Formularwert robust auf den Typ ab. Wirft
     * {@link IllegalArgumentException} bei unbekanntem Wert (fail-closed: kein Rueckfall auf ein Standardformat).
     */
    public static GeneratedFileType fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Dateityp fehlt.");
        }
        try {
            return GeneratedFileType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Bewusst KEINE internen Details (Klassennamen o. ae.) an die Oberflaeche durchreichen.
            throw new IllegalArgumentException("Unbekannter Dateityp.");
        }
    }
}
