package de.internal.awareness.tracking;

import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.recipient.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Nur-Lese-Repository fuer die Detailseite einer einzelnen {@link MailDelivery} (Composer-Tracking) samt ihrer
 * Ereignis-Timeline.
 *
 * <p>Bewusst getrennt von {@code MailDeliveryRepository} und {@code TrackingDashboardRepository}, damit die
 * detail-spezifischen Projektionen isoliert und ohne Aenderung der bestehenden Repositories liegen. Formal ueber
 * {@link MailDelivery} definiert; die Timeline-Abfrage liest jedoch aus {@link MailTrackingEvent}.</p>
 *
 * <p>Sicherheit/Datenschutz: Keine der Abfragen selektiert jemals die Spalte {@code tracking_token_hash} oder
 * sonstige Token; ebenso werden keine IP-Adressen, User-Agents oder Fingerprints gelesen (existieren im
 * Datenmodell gar nicht). Es werden ausschliesslich die ohnehin bekannte Empfaenger-Identitaet (Name/E-Mail),
 * Versandvorgang, Anhangname, Zeitpunkte, Status, Versuchszaehler sowie die Ereignisse (Typ + Zeitpunkt) gelesen.</p>
 *
 * <p>N+1-Vermeidung: Die Detailseite benoetigt genau ZWEI Abfragen, unabhaengig von der Anzahl der Ereignisse -
 * {@link #findDetailById(Long)} (eine Zeile per Join ueber Kontakt und Batch, kein Lazy-Nachladen) und
 * {@link #findTimeline(Long)} (eine geordnete Abfrage aller Ereignisse). Es wird KEINE Abfrage je Ereignis
 * ausgefuehrt.</p>
 */
public interface TrackingDeliveryRepository extends JpaRepository<MailDelivery, Long> {

    /**
     * Detailprojektion genau einer Zustellung (per Id) - ohne jeglichen Token. Empfaenger-Identitaet
     * (Anzeigename, E-Mail), Versandvorgang (Id, Betreff, Anhangname, Erstellzeit) und Zustell-Metadaten
     * (Sendezeit, Status, Versuchszaehler, Erstellzeit) werden per Join direkt mitgeladen (kein N+1). Da die Id
     * der Primaerschluessel ist, liefert die Abfrage hoechstens eine Zeile.
     */
    @Query("""
            select d.id as deliveryId,
                   c.displayName as recipientName,
                   c.email as email,
                   b.id as batchId,
                   b.subject as batchSubject,
                   b.attachmentFilename as attachmentFilename,
                   d.sentAt as sentAt,
                   d.status as status,
                   d.attemptCount as attemptCount,
                   b.createdAt as batchCreatedAt,
                   d.createdAt as deliveryCreatedAt
            from MailDelivery d
                 join d.contact c
                 join d.batch b
            where d.id = :id
            """)
    Optional<DeliveryDetailView> findDetailById(@Param("id") Long id);

    /**
     * Alle Ereignisse einer Zustellung als schlanke Projektion (Typ + Zeitpunkt), chronologisch aufsteigend nach
     * {@code occurredAt} (mit der Ereignis-Id als deterministischem Tiebreaker bei identischem Zeitpunkt). Eine
     * einzige Abfrage - unabhaengig von der Anzahl der Ereignisse.
     */
    @Query("""
            select e.type as type,
                   e.occurredAt as occurredAt
            from MailTrackingEvent e
            where e.delivery.id = :id
            order by e.occurredAt asc, e.id asc
            """)
    List<TimelineEventView> findTimeline(@Param("id") Long id);

    /**
     * Schlanke Projektion einer Zustellung fuer die Detailseite. Enthaelt bewusst KEINEN Token/Hash.
     */
    interface DeliveryDetailView {
        Long getDeliveryId();

        /** Anzeigename des Empfaengers; kann {@code null} sein. */
        String getRecipientName();

        String getEmail();

        Long getBatchId();

        String getBatchSubject();

        /** Anhang-Downloadname des Versandvorgangs; {@code null}, wenn ohne Anhang versendet. */
        String getAttachmentFilename();

        /** Sendezeitpunkt der Zustellung; {@code null}, solange nicht erfolgreich versendet. */
        Instant getSentAt();

        DeliveryStatus getStatus();

        int getAttemptCount();

        Instant getBatchCreatedAt();

        Instant getDeliveryCreatedAt();
    }

    /**
     * Schlanke Projektion eines einzelnen Tracking-Ereignisses (Typ + Zeitpunkt).
     */
    interface TimelineEventView {
        TrackingEventType getType();

        Instant getOccurredAt();
    }
}
