package de.internal.awareness.contact;

/**
 * Fachlicher Fehler: Die angegebene Adresse existiert in der globalen Kontaktliste bereits. Grundlage
 * einer verstaendlichen Duplikat-Meldung an der Oberflaeche; der Unique-Index (email COLLATE NOCASE)
 * bleibt der endgueltige DB-seitige Schutz.
 *
 * <p>Im Unterschied zu {@code de.internal.awareness.recipient.DuplicateRecipientException} bezieht sich
 * dieser Fehler auf die kampagnenunabhaengige, globale Kontaktliste: Eine Adresse ist dort insgesamt nur
 * einmal erlaubt.</p>
 */
public class DuplicateContactException extends RuntimeException {

    private final String email;

    public DuplicateContactException(String email) {
        super("Kontakt bereits vorhanden: " + email);
        this.email = email;
    }

    /** Die betroffene E-Mail-Adresse (im urspruenglich uebergebenen Wortlaut). */
    public String getEmail() {
        return email;
    }
}
