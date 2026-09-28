package de.internal.awareness.web.api.dto;

import java.time.Instant;

/**
 * JSON-Uebertragungsobjekt fuer eine Auswertungszeile je Versandvorgang ({@code GET /api/tracking/batches}).
 *
 * <p>{@code recipientCount} zaehlt ALLE Zustellungen des Batches (ueber alle Status). {@code reactedRecipients}
 * und {@code totalActions} stammen ausschliesslich aus den getrackten Zustellungen und deren registrierten
 * Ereignissen. Zeitpunkte sind rohe {@link Instant} (Jackson: ISO-8601-Zeichenkette, UTC); die Formatierung ist
 * Aufgabe des Frontends. Es werden niemals Token, Token-Hashes oder sonstige Geheimnisse ausgegeben.</p>
 *
 * @param batchId            technische Id des Versandvorgangs
 * @param subject            Betreff des Versandvorgangs
 * @param attachmentFilename Anhang-Downloadname ({@code null}, wenn ohne Anhang versendet)
 * @param createdAt          Erstellzeitpunkt des Versandvorgangs
 * @param recipientCount     Anzahl ALLER Zustellungen des Batches (ueber alle Status)
 * @param sentCount          Anzahl erfolgreich versendeter Zustellungen ({@code SENT})
 * @param failedCount        Anzahl fehlgeschlagener Zustellungen ({@code FAILED})
 * @param notSentCount       Anzahl noch nicht versendeter Zustellungen ({@code NOT_SENT})
 * @param reactedRecipients  Anzahl getrackter Zustellungen mit mindestens einer Aktion
 * @param reactionRate       Aktionsquote {@code reactedRecipients / recipientCount} (0.0, falls keine Zustellung)
 * @param totalActions       Gesamtzahl der Aktionen ueber die getrackten Zustellungen
 * @param firstEvent         Zeitpunkt der ersten Aktion ({@code null}, falls keine)
 * @param lastEvent          Zeitpunkt der letzten Aktion ({@code null}, falls keine)
 */
public record BatchStatDto(Long batchId, String subject, String attachmentFilename, Instant createdAt,
                           long recipientCount, long sentCount, long failedCount, long notSentCount,
                           long reactedRecipients, double reactionRate, long totalActions,
                           Instant firstEvent, Instant lastEvent) {
}
