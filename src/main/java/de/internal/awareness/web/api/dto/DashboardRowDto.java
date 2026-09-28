package de.internal.awareness.web.api.dto;

import java.time.Instant;

/**
 * JSON-Uebertragungsobjekt fuer genau eine Zeile des Tracking-Dashboards (eine getrackte Zustellung samt ihrer
 * Klick-Auswertung).
 *
 * <p>Zeitpunkte werden als roher {@link Instant} ausgegeben; Jackson serialisiert diese standardmaessig als
 * ISO-8601-Zeichenketten (UTC). Die anzeigebezogene Formatierung (z. B. Europe/Berlin) ist bewusst Aufgabe des
 * Frontends. Es werden ausschliesslich bereits bekannte, nicht sensible Anzeige-Daten ausgegeben - niemals
 * Token, Token-Hashes, IP-Adressen oder sonstige Telemetrie.</p>
 *
 * @param deliveryId         technische Id der Zustellung
 * @param contactName        Anzeigename des Empfaengers ({@code null} moeglich)
 * @param contactEmail       E-Mail-Adresse des Empfaengers
 * @param batchId            Id des Versandvorgangs
 * @param subject            Betreff des Versandvorgangs
 * @param attachmentFilename Anhang-Downloadname ({@code null}, wenn ohne Anhang)
 * @param deliveryStatus     Versandstatus als Name ({@code NOT_SENT}/{@code SENT}/{@code FAILED})
 * @param sentAt             Sendezeitpunkt ({@code null}, solange nicht erfolgreich versendet)
 * @param reacted            ob der Trainingslink mindestens einmal ausgeloest wurde
 * @param firstClick         Zeitpunkt des ersten Klicks ({@code null}, falls keiner)
 * @param lastClick          Zeitpunkt des letzten Klicks ({@code null}, falls keiner)
 * @param clickCount         Anzahl der Klicks
 */
public record DashboardRowDto(Long deliveryId, String contactName, String contactEmail, Long batchId,
                              String subject, String attachmentFilename, String deliveryStatus, Instant sentAt,
                              boolean reacted, Instant firstClick, Instant lastClick, long clickCount) {
}
