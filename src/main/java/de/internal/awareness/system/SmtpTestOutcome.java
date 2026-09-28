package de.internal.awareness.system;

/**
 * Sichere, feste Kategorien des SMTP-Verbindungstests. Die zugehoerige Meldung ist bewusst allgemein
 * gehalten und enthaelt NIE rohe SMTP-Ausnahmen, Serverantworten, Credentials oder Stacktraces.
 */
public enum SmtpTestOutcome {

    SUCCESS("Verbindung zum SMTP-Server erfolgreich."),
    HOST_UNREACHABLE("SMTP-Host nicht erreichbar."),
    AUTH_FAILED("Authentifizierung fehlgeschlagen."),
    TLS_FAILED("TLS/SSL-Konfiguration fehlgeschlagen."),
    TIMEOUT("Zeitüberschreitung beim Verbindungsaufbau."),
    INCOMPLETE_CONFIG("SMTP-Konfiguration unvollständig."),
    OTHER_ERROR("Verbindungsfehler.");

    private final String message;

    SmtpTestOutcome(String message) {
        this.message = message;
    }

    /** Menschenlesbare, sanitizte Standardmeldung fuer diese Kategorie. */
    public String message() {
        return message;
    }

    /** Ob der Test erfolgreich war. */
    public boolean success() {
        return this == SUCCESS;
    }
}
