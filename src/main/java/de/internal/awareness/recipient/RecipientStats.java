package de.internal.awareness.recipient;

/**
 * Aufschluesselung der Empfaenger einer Kampagne nach Versandstatus (fuer die Uebersicht).
 *
 * @param total   Gesamtzahl der Empfaenger der Kampagne
 * @param notSent Anzahl der noch nicht (erfolgreich) versendeten Empfaenger ({@code NOT_SENT})
 * @param sent    Anzahl der erfolgreich versendeten Empfaenger ({@code SENT})
 * @param failed  Anzahl der fehlgeschlagenen Versandversuche ({@code FAILED})
 */
public record RecipientStats(long total, long notSent, long sent, long failed) {
}
