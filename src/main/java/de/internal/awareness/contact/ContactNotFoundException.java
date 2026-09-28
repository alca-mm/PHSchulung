package de.internal.awareness.contact;

/**
 * Fachlicher Fehler: Es wurde ein Kontakt ueber eine ID angefragt, die nicht existiert. Bewusst schlank.
 * Wird u. a. vom E-Mail-Composer genutzt, um manipulierte/nicht vorhandene Empfaenger-IDs abzulehnen.
 */
public class ContactNotFoundException extends RuntimeException {

    private final Long contactId;

    public ContactNotFoundException(Long contactId) {
        super("Kontakt nicht gefunden: " + contactId);
        this.contactId = contactId;
    }

    public Long getContactId() {
        return contactId;
    }
}
