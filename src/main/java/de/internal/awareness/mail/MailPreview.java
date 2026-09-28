package de.internal.awareness.mail;

import de.internal.awareness.contact.Contact;

import java.util.List;

/**
 * Reine Anzeige-DTO fuer die Versand-VORSCHAU (Dry-Run). Wird NICHT persistiert und loest KEINEN Versand aus.
 *
 * <p>Fuer das Tracking wird bewusst KEIN echter, persistenter Token erzeugt: {@link #trackingSampleLink()}
 * ist lediglich ein Platzhaltertext (z. B. {@code .../t/<individueller-token>}). Die enthaltenen
 * {@link Contact}s tragen keine Secrets (nur Anzeigename/Adresse); Tracking-Tokens/-Hashes sind nicht Teil
 * dieser DTO.</p>
 */
public record MailPreview(
        String senderEmail,
        String senderName,
        String subject,
        String body,
        int recipientCount,
        List<Contact> recipients,
        boolean hasAttachment,
        String attachmentDisplayName,
        String attachmentDownloadFilename,
        String attachmentType,
        long attachmentSize,
        Long attachmentId,
        boolean trackingEnabled,
        String trackingBaseUrl,
        String trackingSampleLink,
        boolean liveSendEnabled) {
}
