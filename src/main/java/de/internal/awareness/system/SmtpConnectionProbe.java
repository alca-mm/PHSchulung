package de.internal.awareness.system;

import jakarta.mail.MessagingException;

/**
 * Schmale Abstraktion fuer den reinen SMTP-Verbindungsaufbau (Naht/Seam) - damit der Verbindungstest
 * deterministisch ohne echtes SMTP getestet werden kann. Die Standard-Implementierung
 * {@link JavaMailSmtpConnectionProbe} baut eine kurzlebige Verbindung auf und schliesst sie sofort wieder;
 * es wird dabei NIEMALS eine E-Mail versendet.
 */
@FunctionalInterface
public interface SmtpConnectionProbe {

    /**
     * Baut testweise eine Verbindung zum konfigurierten SMTP-Server auf (inkl. Authentifizierung, falls
     * aktiviert) und schliesst sie wieder. Wirft {@link MessagingException} (ggf. mit technischer Ursache),
     * wenn der Aufbau fehlschlaegt. Versendet KEINE E-Mail.
     */
    void connect() throws MessagingException;
}
