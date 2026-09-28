package de.internal.awareness.mail;

import java.util.List;

/**
 * Zusammenfassung eines Versandlaufs ueber alle Empfaenger einer Kampagne.
 *
 * <p>Datenschutz/Sicherheit: enthaelt bewusst keine Secrets, Tokens oder Serverantworten. Die Listen
 * {@code failedEmails}/{@code blockedEmails} dienen der Anzeige in der internen Oberflaeche.</p>
 *
 * @param attempted                 Anzahl tatsaechlich versuchter Versendungen (ohne uebersprungene/blockierte)
 * @param sent                      Anzahl erfolgreich uebergebener Nachrichten
 * @param failed                    Anzahl fehlgeschlagener Versuche
 * @param skippedAlreadySent        Anzahl uebersprungener, bereits versendeter Empfaenger
 * @param blockedByRecipientAllowlist Anzahl wegen Empfaenger-Domain-Allowlist blockierter Empfaenger
 * @param failedEmails              Adressen, deren Versand fehlgeschlagen ist
 * @param blockedEmails             Adressen, die durch die Empfaenger-Domain-Allowlist blockiert wurden
 */
public record SendSummary(int attempted, int sent, int failed, int skippedAlreadySent,
                          int blockedByRecipientAllowlist,
                          List<String> failedEmails, List<String> blockedEmails) {
}
