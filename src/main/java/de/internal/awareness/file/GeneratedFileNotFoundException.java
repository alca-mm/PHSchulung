package de.internal.awareness.file;

/**
 * Fachlicher Fehler: Es wurde eine Datei ueber eine ID angefragt, die nicht existiert (oder deren
 * Inhalt fehlt). Bewusst schlank. Die Weboberflaeche uebersetzt dies in eine kontrollierte 404-Antwort
 * (siehe {@code web}-Paket). Es werden bewusst KEINE internen Details (Pfade o. ae.) preisgegeben.
 */
public class GeneratedFileNotFoundException extends RuntimeException {

    private final Long fileId;

    public GeneratedFileNotFoundException(Long fileId) {
        super("Datei nicht gefunden: " + fileId);
        this.fileId = fileId;
    }

    public Long getFileId() {
        return fileId;
    }
}
