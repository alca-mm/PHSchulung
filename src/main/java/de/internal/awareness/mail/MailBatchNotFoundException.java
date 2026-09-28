package de.internal.awareness.mail;

/**
 * Fachlicher Fehler: Es wurde ein Versandvorgang ueber eine ID angefragt, die nicht existiert. Bewusst
 * schlank; die Weboberflaeche uebersetzt dies in eine kontrollierte 404-Antwort (siehe {@code web}-Paket).
 */
public class MailBatchNotFoundException extends RuntimeException {

    private final Long batchId;

    public MailBatchNotFoundException(Long batchId) {
        super("Versandvorgang nicht gefunden: " + batchId);
        this.batchId = batchId;
    }

    public Long getBatchId() {
        return batchId;
    }
}
