package de.internal.awareness.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Formularobjekt zum komfortablen Hinzufuegen mehrerer Kontakte zur globalen Empfaengerliste. Pro Zeile
 * entweder {@code email@example.invalid} oder {@code Name <email@example.invalid>}. Leerzeilen werden
 * ignoriert, ungueltige Zeilen verstaendlich gemeldet, Duplikate case-insensitive uebersprungen
 * (Verarbeitung im BulkRecipientParser/ContactService).
 */
public class BulkContactForm {

    @NotBlank
    @Size(max = 100_000)
    private String text;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
