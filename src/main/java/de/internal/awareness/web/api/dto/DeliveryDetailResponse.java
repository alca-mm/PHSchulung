package de.internal.awareness.web.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * JSON-Antwort des Detail-Endpoints ({@code GET /api/tracking/deliveries/{id}}) fuer genau eine Zustellung.
 *
 * <p>Bewusst in fachliche Teilobjekte gegliedert ({@link Recipient}, {@link Delivery}, {@link Tracking},
 * {@code timeline}). Es werden ausschliesslich die ohnehin bekannte Empfaenger-Identitaet sowie Zustell-,
 * Batch-, Datei-, Zeit-, Status- und Aktionsdaten und die Ereignis-Timeline (nur Typ + Zeitpunkt) ausgegeben -
 * niemals Token, Token-Hashes, IP-Adressen oder sonstige Telemetrie.</p>
 *
 * @param recipient bekannte Empfaenger-Identitaet
 * @param delivery  Zustell-/Batch-/Datei-/Status-Metadaten
 * @param tracking  aufbereitete Klick-Auswertung
 * @param timeline  chronologisch aufsteigende Liste aller Ereignisse (kann leer sein)
 */
public record DeliveryDetailResponse(Recipient recipient, Delivery delivery, Tracking tracking,
                                     List<TimelineEntryDto> timeline) {

    /**
     * Bekannte Empfaenger-Identitaet.
     *
     * @param name  Anzeigename ({@code null} moeglich)
     * @param email E-Mail-Adresse
     */
    public record Recipient(String name, String email) {
    }

    /**
     * Zustell- und Batch-Metadaten der Zustellung.
     *
     * @param deliveryId         technische Id der Zustellung
     * @param batchId            Id des Versandvorgangs
     * @param subject            Betreff des Versandvorgangs
     * @param attachmentFilename Anhang-Downloadname ({@code null}, wenn ohne Anhang)
     * @param deliveryStatus     Versandstatus als Name ({@code NOT_SENT}/{@code SENT}/{@code FAILED})
     * @param sentAt             Sendezeitpunkt ({@code null}, solange nicht erfolgreich versendet)
     * @param attemptCount       Anzahl der Versandversuche
     */
    public record Delivery(Long deliveryId, Long batchId, String subject, String attachmentFilename,
                           String deliveryStatus, Instant sentAt, int attemptCount) {
    }

    /**
     * Aufbereitete Klick-Auswertung der Zustellung.
     *
     * @param reacted    ob der Trainingslink mindestens einmal ausgeloest wurde
     * @param firstClick Zeitpunkt des ersten Ereignisses ({@code null}, falls keines)
     * @param lastClick  Zeitpunkt des letzten Ereignisses ({@code null}, falls keines)
     * @param clickCount Gesamtzahl der Ereignisse
     */
    public record Tracking(boolean reacted, Instant firstClick, Instant lastClick, long clickCount) {
    }
}
