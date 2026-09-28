package de.internal.awareness.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Formular-Bean fuer den globalen E-Mail-Composer (Betreff, Text, optionaler Anhang).
 *
 * <p>Bindet ausschliesslich die inhaltlichen Felder der Nachricht. Der Absender wird bewusst NICHT hier
 * gebunden, sondern serverseitig aus der Konfiguration ({@code app.mail.default-sender}) uebernommen; die
 * Empfaengerauswahl und die Autorisierungs-Bestaetigung werden als separate Request-Parameter erfasst und
 * vom Server erneut geprueft. Das leere Auswahlfeld fuer den Anhang bindet auf {@code null} (kein Anhang).</p>
 */
public class MailComposeForm {

    /** Betreff der Nachricht (Pflichtfeld). */
    @NotBlank
    @Size(max = 255)
    private String subject;

    /** Textkoerper der Nachricht (Pflichtfeld). */
    @NotBlank
    @Size(max = 10000)
    private String body;

    /** Optionale interne Id des anzuhaengenden Dokuments; leeres Formularfeld bindet auf {@code null}. */
    private Long generatedFileId;

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Long getGeneratedFileId() {
        return generatedFileId;
    }

    public void setGeneratedFileId(Long generatedFileId) {
        this.generatedFileId = generatedFileId;
    }
}
