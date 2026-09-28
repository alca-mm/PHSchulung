package de.internal.awareness.mail;

import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;

/**
 * Ableitung einer kurzen, festen und sanitizten Fehlerkategorie fuer fehlgeschlagene Versandversuche.
 *
 * <p>Datenschutz/Sicherheit: die Kategorie wird AUSSCHLIESSLICH aus dem Ausnahme-<b>Typ</b> bestimmt,
 * niemals aus der Ausnahme-Meldung oder einer SMTP-Serverantwort. Dadurch koennen ueber die dauerhaft
 * gespeicherte {@code failure_category} keine sensiblen Serverdetails oder Zugangsdaten leaken. Die
 * zurueckgegebenen Tokens sind bewusst kurz (weit unter 64 Zeichen, passend zur DB-Spaltenlaenge).</p>
 */
public final class MailFailureCategory {

    /** Authentifizierung am SMTP-Server fehlgeschlagen (z. B. falsche/abgelaufene Zugangsdaten). */
    public static final String AUTH = "AUTH";

    /** Uebergabe/Versand am SMTP-Server fehlgeschlagen (z. B. abgelehnte Empfaenger, Transportfehler). */
    public static final String SEND = "SEND";

    /** Konfigurations-/Aufbereitungsfehler der Nachricht (Parse-/Preparation-Fehler). */
    public static final String CONFIG = "CONFIG";

    /** Alle uebrigen, nicht naeher kategorisierten Fehler. */
    public static final String OTHER = "OTHER";

    private MailFailureCategory() {
        // Utility-Klasse: keine Instanzen.
    }

    /**
     * Bildet eine Ausnahme ausschliesslich anhand ihres Typs auf eine kurze, sanitizte Kategorie ab.
     * Es wird bewusst NIE {@link Throwable#getMessage()} ausgewertet.
     *
     * @param t die aufgetretene Ausnahme (darf {@code null} sein)
     * @return kurzer, fester Token ({@code AUTH}, {@code SEND}, {@code CONFIG} oder {@code OTHER})
     */
    public static String of(Throwable t) {
        if (t instanceof MailAuthenticationException) {
            return AUTH;
        }
        if (t instanceof MailSendException) {
            return SEND;
        }
        if (t instanceof MailParseException || t instanceof MailPreparationException) {
            return CONFIG;
        }
        return OTHER;
    }
}
