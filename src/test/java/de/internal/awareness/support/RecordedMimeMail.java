package de.internal.awareness.support;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ausgelesene, gut pruefbare Repraesentation einer versendeten {@link MimeMessage} (fuer Attachment-Tests).
 * Extrahiert Absender, Empfaenger (To/CC/BCC), Betreff, Plaintext-Koerper und Anhaenge, damit Tests u. a.
 * pruefen koennen: genau ein To je Mail, kein CC/BCC, korrekter Anhang-Dateiname und -Content-Type.
 */
public record RecordedMimeMail(String from, List<String> to, List<String> cc, List<String> bcc,
                               String subject, String body, List<Attachment> attachments) {

    /** Ein ausgelesener Anhang. {@code contentType} kann Parameter enthalten (z. B. name=...). */
    public record Attachment(String filename, String contentType, byte[] bytes) {

        /** Content-Type ohne Parameter (Teil vor dem ersten ';'), getrimmt und klein geschrieben. */
        public String baseContentType() {
            if (contentType == null) {
                return null;
            }
            int semicolon = contentType.indexOf(';');
            String base = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
            return base.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** Liest eine {@link MimeMessage} in eine {@link RecordedMimeMail} aus. */
    public static RecordedMimeMail from(MimeMessage message) {
        try {
            String from = firstAddress(message.getFrom());
            List<String> to = addresses(message.getRecipients(Message.RecipientType.TO));
            List<String> cc = addresses(message.getRecipients(Message.RecipientType.CC));
            List<String> bcc = addresses(message.getRecipients(Message.RecipientType.BCC));
            String subject = message.getSubject();

            String body = null;
            List<Attachment> attachments = new ArrayList<>();
            Object content = message.getContent();
            if (content instanceof MimeMultipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    BodyPart part = multipart.getBodyPart(i);
                    if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                        byte[] bytes = part.getInputStream().readAllBytes();
                        attachments.add(new Attachment(part.getFileName(), part.getContentType(), bytes));
                    } else if (body == null) {
                        body = partAsText(part);
                    }
                }
            } else if (content != null) {
                body = content.toString();
            }
            return new RecordedMimeMail(from, to, cc, bcc, subject, body, attachments);
        } catch (Exception e) {
            throw new IllegalStateException("MimeMessage konnte nicht ausgelesen werden.", e);
        }
    }

    private static String partAsText(BodyPart part) throws Exception {
        Object content = part.getContent();
        return content == null ? null : content.toString();
    }

    private static String firstAddress(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return null;
        }
        return addresses[0].toString();
    }

    private static List<String> addresses(Address[] addresses) {
        List<String> result = new ArrayList<>();
        if (addresses != null) {
            for (Address address : addresses) {
                if (address instanceof InternetAddress internet) {
                    result.add(internet.getAddress());
                } else if (address != null) {
                    result.add(address.toString());
                }
            }
        }
        return result;
    }
}
