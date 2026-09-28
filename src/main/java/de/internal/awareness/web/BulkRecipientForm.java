package de.internal.awareness.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Formularobjekt zum komfortablen Hinzufuegen mehrerer Empfaenger. Pro Zeile entweder
 * {@code email@example.invalid} oder {@code Anzeigename <email@example.invalid>}. Leerzeilen werden
 * ignoriert, ungueltige Zeilen verstaendlich gemeldet (Verarbeitung im BulkRecipientParser/RecipientService).
 */
public class BulkRecipientForm {

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
