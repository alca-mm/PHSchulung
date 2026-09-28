package de.internal.awareness.support;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Test-Doppel fuer {@link JavaMailSender}: verschickt NICHTS ueber echtes SMTP, sondern zeichnet die
 * uebergebenen {@link SimpleMailMessage}s auf. Damit lassen sich Empfaenger, Absender, Betreff und Text
 * je erzeugter Einzelmail pruefen (u. a.: jeder Empfaenger erhaelt genau eine eigene Nachricht, keine
 * Fremdadressen in To/CC).
 *
 * <p>Fuer Fehlerszenarien koennen einzelne Empfaengeradressen als "fehlschlagend" markiert werden
 * ({@link #failForRecipients}); ein Versand an eine solche Adresse wirft eine {@link MailSendException}
 * (ohne sensible Details), waehrend die uebrigen Empfaenger normal weiterverarbeitet werden.</p>
 *
 * <p>Bewusst KEINE echten SMTP-Zugangsdaten: dieses Doppel benoetigt keinen Host und kein Passwort.</p>
 */
public class RecordingJavaMailSender implements JavaMailSender {

    private final List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();
    private final List<RecordedMimeMail> sentMime = new CopyOnWriteArrayList<>();
    private final Set<String> failingRecipients = new CopyOnWriteArraySet<>();

    /** Setzt aufgezeichnete Nachrichten und Fehlerregeln zurueck (zwischen Testmethoden aufrufen). */
    public void reset() {
        sent.clear();
        sentMime.clear();
        failingRecipients.clear();
    }

    /** Markiert Empfaengeradressen (case-insensitive), deren Versand eine {@link MailSendException} wirft. */
    public void failForRecipients(String... recipients) {
        for (String recipient : recipients) {
            if (recipient != null) {
                failingRecipients.add(recipient.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    /** Alle bisher erfolgreich aufgezeichneten Nachrichten (in Versandreihenfolge). */
    public List<SimpleMailMessage> getSentMessages() {
        return List.copyOf(sent);
    }

    public int getSentCount() {
        return sent.size();
    }

    /** Alle bisher erfolgreich aufgezeichneten MimeMessages (ausgelesen), in Versandreihenfolge. */
    public List<RecordedMimeMail> getSentMimeMails() {
        return List.copyOf(sentMime);
    }

    public int getSentMimeCount() {
        return sentMime.size();
    }

    @Override
    public void send(SimpleMailMessage simpleMessage) throws MailSendException {
        failIfMarked(simpleMessage);
        sent.add(copyOf(simpleMessage));
    }

    @Override
    public void send(SimpleMailMessage... simpleMessages) throws MailSendException {
        for (SimpleMailMessage message : simpleMessages) {
            send(message);
        }
    }

    private void failIfMarked(SimpleMailMessage message) {
        String[] to = message.getTo();
        if (to != null) {
            for (String recipient : to) {
                if (recipient != null && failingRecipients.contains(recipient.trim().toLowerCase(Locale.ROOT))) {
                    // Bewusst generische Meldung ohne sensible Details.
                    throw new MailSendException("simulated delivery failure");
                }
            }
        }
    }

    private void failIfMarked(MimeMessage message) {
        try {
            Address[] to = message.getRecipients(Message.RecipientType.TO);
            if (to != null) {
                for (Address address : to) {
                    String email = address instanceof InternetAddress internet
                            ? internet.getAddress() : String.valueOf(address);
                    if (email != null && failingRecipients.contains(email.trim().toLowerCase(Locale.ROOT))) {
                        throw new MailSendException("simulated delivery failure");
                    }
                }
            }
        } catch (jakarta.mail.MessagingException e) {
            throw new MailSendException("could not read recipients", e);
        }
    }

    private static SimpleMailMessage copyOf(SimpleMailMessage source) {
        SimpleMailMessage copy = new SimpleMailMessage();
        copy.setFrom(source.getFrom());
        copy.setTo(source.getTo() == null ? null : Arrays.copyOf(source.getTo(), source.getTo().length));
        copy.setCc(source.getCc() == null ? null : Arrays.copyOf(source.getCc(), source.getCc().length));
        copy.setBcc(source.getBcc() == null ? null : Arrays.copyOf(source.getBcc(), source.getBcc().length));
        copy.setReplyTo(source.getReplyTo());
        copy.setSubject(source.getSubject());
        copy.setText(source.getText());
        return copy;
    }

    // --- MimeMessage-Teil des Interfaces: fuer den globalen Composer (Anhaenge) benoetigt. ---

    @Override
    public MimeMessage createMimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Override
    public MimeMessage createMimeMessage(InputStream contentStream) {
        try {
            return new MimeMessage(Session.getInstance(new Properties()), contentStream);
        } catch (Exception e) {
            throw new MailSendException("createMimeMessage failed", e);
        }
    }

    @Override
    public void send(MimeMessage mimeMessage) throws MailSendException {
        failIfMarked(mimeMessage);
        try {
            // Wie beim echten Versand (Transport.send) die Header aus den DataHandlern materialisieren,
            // damit u. a. der Content-Type der Anhaenge korrekt auslesbar ist.
            mimeMessage.saveChanges();
        } catch (jakarta.mail.MessagingException e) {
            throw new MailSendException("could not finalize message", e);
        }
        // Ausgelesene Kopie aufzeichnen (To/CC/BCC, Betreff, Koerper, Anhaenge pruefbar). Kein echtes SMTP.
        sentMime.add(RecordedMimeMail.from(mimeMessage));
    }

    @Override
    public void send(MimeMessage... mimeMessages) throws MailSendException {
        for (MimeMessage message : mimeMessages) {
            send(message);
        }
    }
}
