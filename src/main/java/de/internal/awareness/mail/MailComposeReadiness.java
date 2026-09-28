package de.internal.awareness.mail;

import java.util.List;

/**
 * Ergebnis der Vorab-Pruefung, ob der globale E-Mail-Composer versandbereit ist (unabhaengig von einer
 * konkreten Nachricht/Empfaengerauswahl).
 *
 * @param ready           {@code true}, wenn keine Vorbedingung verletzt ist ({@code blockers} leer)
 * @param blockers        menschenlesbare (deutsche) Gruende, warum (noch) nicht versendet werden darf
 * @param senderEmail     die konfigurierte, sichtbare Absenderadresse (KEIN Secret; {@code null}, wenn keine)
 * @param senderName      optionaler Absender-Anzeigename
 * @param liveSendEnabled ob echter SMTP-Versand aktiviert ist
 */
public record MailComposeReadiness(boolean ready, List<String> blockers, String senderEmail, String senderName,
                                   boolean liveSendEnabled) {
}
