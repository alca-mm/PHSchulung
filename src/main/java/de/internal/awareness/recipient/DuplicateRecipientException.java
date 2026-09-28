package de.internal.awareness.recipient;

/**
 * Fachlicher Fehler: Die angegebene Adresse existiert in dieser Kampagne bereits. Grundlage einer
 * verstaendlichen Duplikat-Meldung an der Oberflaeche; der Unique-Index {@code (campaign_id, email)}
 * bleibt der endgueltige DB-seitige Schutz.
 *
 * <p>Dieselbe Adresse in verschiedenen Kampagnen ist ausdruecklich erlaubt und loest diesen Fehler
 * nicht aus.</p>
 */
public class DuplicateRecipientException extends RuntimeException {

    private final String email;

    public DuplicateRecipientException(String email) {
        super("Empfaenger bereits in dieser Kampagne vorhanden: " + email);
        this.email = email;
    }

    /** Die betroffene E-Mail-Adresse (im urspruenglich uebergebenen Wortlaut). */
    public String getEmail() {
        return email;
    }
}
