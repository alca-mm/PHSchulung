package de.internal.awareness.system;

import java.util.List;

/**
 * Sichere, ausschliesslich fuer die Diagnose bestimmte Statusdarstellung des Systems.
 *
 * <p>Diese Struktur wird bewusst als eigene DTO gebaut (nicht die ConfigurationProperties-Objekte selbst an
 * die View gegeben). Sie enthaelt NIEMALS Secrets: kein SMTP-Passwort (auch nicht maskiert/laengenbehaftet),
 * kein Admin-Passwort, keine Passwort-Hashes, keine Tracking-Tokens/-Hashes, keine rohen Environment-Werte.
 * Passwoerter/Benutzer erscheinen nur als "vorhanden: Ja/Nein".</p>
 */
public record SystemStatus(
        AdminStatus admin,
        SmtpStatus smtp,
        SenderStatus sender,
        RecipientAllowlistStatus recipientAllowlist,
        TrackingStatus tracking,
        FileStorageStatus fileStorage,
        DatabaseStatus database,
        OverallReadiness readiness) {

    /** @param configured Admin-Zugang konfiguriert (Benutzername + Passwort gesetzt). @param currentUser angemeldeter Benutzer. */
    public record AdminStatus(boolean configured, String currentUser) {
    }

    /** SMTP-Statuswerte - nur Existenz von Benutzer/Passwort, niemals die Werte selbst. */
    public record SmtpStatus(boolean hostConfigured, String host, int port, boolean portValid,
                             boolean usernamePresent, boolean passwordPresent, boolean authEnabled,
                             boolean starttlsEnabled, boolean liveSendEnabled) {
    }

    /** Absender-Status (keine Secrets; die Absenderadresse ist nicht sensibel). */
    public record SenderStatus(boolean configured, String senderEmail, String senderName,
                               boolean allowedByAllowlist, boolean allowlistConfigured) {
    }

    /** Empfaenger-Domain-Allowlist: leer => keine Beschraenkung; gesetzt => nur diese Domains. */
    public record RecipientAllowlistStatus(boolean active, List<String> domains) {
    }

    /** Tracking-Basis-URL-Status (Validierung ueber die bestehende TrackingLinkPolicy). */
    public record TrackingStatus(boolean configured, String baseUrl, boolean valid, boolean https,
                                 boolean loopback) {
    }

    /** Dateispeicher-Status (nur Status, keine Aenderung ueber die UI). */
    public record FileStorageStatus(String directory, boolean exists, boolean writable, long maxFileSizeBytes) {
    }

    /** Datenbank-/Flyway-Status (keine Tabelleninhalte, keine personenbezogenen Daten). */
    public record DatabaseStatus(boolean reachable, String currentVersion, String expectedVersion,
                                 boolean upToDate) {
    }

    /** Gesamt-Diagnosestatus (nur abgeleitete Bereitschaftsflags). */
    public record OverallReadiness(boolean readyForLocalUse, boolean readyForPreview,
                                   boolean readyForRealSmtp, boolean readyForExternalTracking) {
    }
}
