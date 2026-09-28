package de.internal.awareness.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Formularobjekt zum Anlegen einer Kampagne (Absender + Betreff + E-Mail-Text).
 *
 * <p>Die fachliche Pflicht (gueltiger Absender, nicht-leerer Betreff/Text) wird hier per Bean Validation
 * geprueft - bewusst am Formular und nicht auf der (nullable) Entity, damit Alt-Kampagnen ohne diese
 * Felder gueltig bleiben. Der E-Mail-Text wird spaeter ausschliesslich als Plaintext versendet und NIE
 * als Template/Code interpretiert.</p>
 */
public class CampaignForm {

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 2000)
    private String description;

    @Size(max = 255)
    private String senderName;

    @NotBlank
    @Email
    @Size(max = 255)
    private String senderEmail;

    @NotBlank
    @Size(max = 255)
    private String emailSubject;

    @NotBlank
    @Size(max = 10000)
    private String emailBody;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public void setSenderEmail(String senderEmail) {
        this.senderEmail = senderEmail;
    }

    public String getEmailSubject() {
        return emailSubject;
    }

    public void setEmailSubject(String emailSubject) {
        this.emailSubject = emailSubject;
    }

    public String getEmailBody() {
        return emailBody;
    }

    public void setEmailBody(String emailBody) {
        this.emailBody = emailBody;
    }
}
