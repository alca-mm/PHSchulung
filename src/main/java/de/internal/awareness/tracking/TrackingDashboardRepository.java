package de.internal.awareness.tracking;

import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.recipient.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

/**
 * Nur-Lese-Repository fuer das Admin-Tracking-Dashboard (Composer-Tracking je {@link MailDelivery}).
 *
 * <p>Bewusst getrennt von {@code MailDeliveryRepository} und {@code MailTrackingEventRepository}, damit die
 * dashboard-spezifischen Aggregat-Abfragen isoliert und ohne Aenderung der bestehenden Repositories liegen.
 * Formal ueber {@link MailTrackingEvent} definiert; die beiden Queries lesen jedoch nur die fuer das Dashboard
 * benoetigten, unkritischen Felder.</p>
 *
 * <p>Sicherheit/Datenschutz: Keine der Abfragen selektiert jemals die Spalte {@code tracking_token_hash} oder
 * sonstige Token; ebenso werden keine IP-Adressen, User-Agents oder Fingerprints gelesen (existieren im
 * Datenmodell gar nicht). Der Hash dient ausschliesslich als {@code IS NOT NULL}-Praedikat zur Abgrenzung der
 * "getrackten" Zustellungen und verlaesst die Datenbank nie.</p>
 *
 * <p>N+1-Vermeidung: Das Dashboard benoetigt genau ZWEI Abfragen, unabhaengig von der Anzahl der Zustellungen -
 * {@link #findTrackedDeliveries()} (eine Zeile je getrackter Zustellung) und {@link #aggregateClicksPerDelivery()}
 * (eine Zeile je Zustellung mit mindestens einem Klick, per {@code group by}). Der Service verbindet beide im
 * Speicher ueber die Delivery-Id. Es wird KEINE Abfrage je Zustellung ausgefuehrt.</p>
 */
public interface TrackingDashboardRepository extends JpaRepository<MailTrackingEvent, Long> {

    /**
     * Alle "getrackten" Zustellungen (Trainingslink gesetzt, also {@code tracking_token_hash IS NOT NULL}) als
     * schlanke Projektion - ohne jeglichen Token. Enthaelt auch Zustellungen ohne Klick (diese fehlen in
     * {@link #aggregateClicksPerDelivery()} und erhalten im Service {@code clickCount = 0}).
     *
     * <p>Empfaenger-Identitaet (Anzeigename, E-Mail), Versandvorgang (Id, Betreff, Anhangname, Erstellzeit) und
     * Zustell-Metadaten (Sendezeit, Status, Erstellzeit) werden per Join direkt mitgeladen (kein Lazy-Nachladen,
     * kein N+1).</p>
     */
    @Query("""
            select d.id as deliveryId,
                   c.displayName as recipientName,
                   c.email as email,
                   b.id as batchId,
                   b.subject as batchSubject,
                   b.attachmentFilename as attachmentFilename,
                   b.createdAt as batchCreatedAt,
                   d.sentAt as sentAt,
                   d.status as status,
                   d.createdAt as createdAt
            from MailDelivery d
                 join d.contact c
                 join d.batch b
            where d.trackingTokenHash is not null
            """)
    List<TrackedDeliveryView> findTrackedDeliveries();

    /**
     * Klick-Aggregate je Zustellung mit mindestens einem {@code LINK_CLICK} - in EINER {@code group by}-Abfrage:
     * Anzahl der Klicks sowie erster ({@code min}) und letzter ({@code max}) Klickzeitpunkt. Zustellungen ohne
     * Klick erscheinen hier bewusst nicht.
     */
    @Query("""
            select e.delivery.id as deliveryId,
                   count(e) as clickCount,
                   min(e.occurredAt) as firstClick,
                   max(e.occurredAt) as lastClick
            from MailTrackingEvent e
            where e.delivery.trackingTokenHash is not null
            group by e.delivery.id
            """)
    List<DeliveryClickAggregateView> aggregateClicksPerDelivery();

    /**
     * Schlanke Projektion einer getrackten Zustellung fuer das Dashboard. Enthaelt bewusst KEINEN Token/Hash.
     */
    interface TrackedDeliveryView {
        Long getDeliveryId();

        /** Anzeigename des Empfaengers; kann {@code null} sein. */
        String getRecipientName();

        String getEmail();

        Long getBatchId();

        String getBatchSubject();

        /** Anhang-Downloadname des Versandvorgangs; {@code null}, wenn ohne Anhang versendet. */
        String getAttachmentFilename();

        Instant getBatchCreatedAt();

        /** Sendezeitpunkt der Zustellung; {@code null}, solange nicht erfolgreich versendet. */
        Instant getSentAt();

        DeliveryStatus getStatus();

        Instant getCreatedAt();
    }

    /**
     * Aggregierte Klickzahlen einer Zustellung (nur fuer Zustellungen mit mindestens einem Klick).
     */
    interface DeliveryClickAggregateView {
        Long getDeliveryId();

        long getClickCount();

        Instant getFirstClick();

        Instant getLastClick();
    }
}
