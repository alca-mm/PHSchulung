package de.internal.awareness.mail;

import java.util.List;

/**
 * Zusammenfassung eines Versandvorgangs des globalen E-Mail-Composers.
 *
 * <p>Datenschutz/Sicherheit: enthaelt bewusst keine Secrets, Tokens oder Serverantworten. Die Listen
 * dienen der Anzeige in der internen Oberflaeche.</p>
 *
 * @param batchId       Id des angelegten {@link MailBatch}
 * @param total         Anzahl der ausgewaehlten Empfaenger
 * @param sent          Anzahl erfolgreich uebergebener Nachrichten
 * @param failed        Anzahl fehlgeschlagener Versuche
 * @param blocked       Anzahl wegen Empfaenger-Domain-Allowlist blockierter Empfaenger (nicht versendet)
 * @param failedEmails  Adressen, deren Versand fehlgeschlagen ist
 * @param blockedEmails Adressen, die durch die Empfaenger-Domain-Allowlist blockiert wurden
 */
public record MailComposeSummary(Long batchId, int total, int sent, int failed, int blocked,
                                 List<String> failedEmails, List<String> blockedEmails) {
}
