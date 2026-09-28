package de.internal.awareness.file;

/**
 * Fachlicher Fehler: Der erzeugte Datei-Inhalt ueberschreitet das konfigurierte Groessenlimit. Erzeugte
 * passive Dokumente (DOCX/XML/PDF/XLSX/PPTX/TXT/CSV) sind normalerweise klein; diese Ausnahme ist ein
 * Sicherheitsnetz gegen versehentlich
 * riesige Dateien.
 */
public class FileTooLargeException extends RuntimeException {

    public FileTooLargeException(long actualBytes, long maxBytes) {
        super("Datei ist zu gross (" + actualBytes + " Bytes; erlaubt sind maximal " + maxBytes + " Bytes).");
    }
}
