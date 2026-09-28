package de.internal.awareness.tracking;

import de.internal.awareness.mail.MailDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

/**
 * Nur-Lese-Repository fuer die Batch-Auswertung ("Batch-Statistik") des globalen Mail-Composers: je
 * Versandvorgang ({@code MailBatch}) werden aggregierte Zustell- und Ereigniszahlen ermittelt.
 *
 * <p>Bewusst getrennt von {@code MailBatchRepository}, {@code MailDeliveryRepository},
 * {@code MailTrackingEventRepository} und {@code TrackingDashboardRepository}, damit die
 * auswertungsspezifischen Aggregat-Abfragen isoliert und ohne Aenderung der bestehenden Repositories
 * liegen.</p>
 *
 * <p><b>Sicherheit/Datenschutz</b>: Keine der beiden Abfragen selektiert jemals die Spalte
 * {@code tracking_token_hash} oder einen sonstigen Token; ebenso werden keine IP-Adressen, User-Agents
 * oder Fingerprints gelesen (existieren im Datenmodell gar nicht). In der zweiten Abfrage dient der Hash
 * ausschliesslich als {@code IS NOT NULL}-Praedikat zur Abgrenzung der "getrackten" Zustellungen und
 * verlaesst die Datenbank nie. Ausgegeben werden nur bekannte Batch-Metadaten (Betreff, Anhangname,
 * Erstellzeit) sowie aggregierte Zaehler und Ereigniszeitpunkte.</p>
 *
 * <p><b>Performance / N+1</b>: Die Auswertung benoetigt genau ZWEI Abfragen, unabhaengig von der Anzahl
 * der Batches oder Zustellungen - {@link #deliveryCountsPerBatch()} (eine Zeile je Batch, per
 * {@code group by}) und {@link #eventAggregatesPerBatch()} (eine Zeile je Batch mit mindestens einem
 * Ereignis, per {@code group by}). Der Service verbindet beide im Speicher ueber die Batch-Id. Es wird
 * KEINE Abfrage je Batch ausgefuehrt.</p>
 */
public interface TrackingBatchStatsRepository extends JpaRepository<MailDelivery, Long> {

    /**
     * Zustell-Zaehler je Versandvorgang ueber ALLE Zustellungen des Batches (unabhaengig davon, ob ein
     * Trainingslink gesetzt ist) - in EINER {@code group by}-Abfrage: Gesamtzahl der Zustellungen sowie die
     * Aufteilung nach Versandstatus ({@code SENT}, {@code FAILED}, {@code NOT_SENT}). Enthaelt bewusst nur
     * Batches mit mindestens einer Zustellung (Join ueber {@code MailDelivery}).
     *
     * <p>Die Status-Aufteilung wird per {@code sum(case ...)} berechnet; fuer jede Batch-Gruppe existiert
     * mindestens eine Zeile, daher liefert {@code count}/{@code sum} stets einen Wert (nie {@code null}).</p>
     */
    @Query("""
            select b.id as batchId,
                   b.subject as subject,
                   b.attachmentFilename as attachmentFilename,
                   b.createdAt as batchCreatedAt,
                   count(d) as totalDeliveries,
                   sum(case when d.status = de.internal.awareness.recipient.DeliveryStatus.SENT then 1 else 0 end) as sentCount,
                   sum(case when d.status = de.internal.awareness.recipient.DeliveryStatus.FAILED then 1 else 0 end) as failedCount,
                   sum(case when d.status = de.internal.awareness.recipient.DeliveryStatus.NOT_SENT then 1 else 0 end) as notSentCount
            from MailDelivery d
                 join d.batch b
            group by b.id, b.subject, b.attachmentFilename, b.createdAt
            """)
    List<BatchDeliveryCountsView> deliveryCountsPerBatch();

    /**
     * Ereignis-Aggregate je Versandvorgang ueber die GETRACKTEN Zustellungen (Trainingslink gesetzt, also
     * {@code tracking_token_hash IS NOT NULL}) - in EINER {@code group by}-Abfrage: Anzahl der Empfaenger mit
     * mindestens einer Aktion ({@code count(distinct delivery)}), Gesamtzahl der Aktionen ({@code count}) sowie
     * erster ({@code min}) und letzter ({@code max}) Ereigniszeitpunkt. Ein Batch ohne jegliches Ereignis
     * erscheint hier bewusst NICHT (der Service setzt dann 0 Responder / 0 Aktionen und {@code null}-Zeiten).
     */
    @Query("""
            select e.delivery.batch.id as batchId,
                   count(distinct e.delivery.id) as respondingRecipients,
                   count(e) as totalActions,
                   min(e.occurredAt) as firstEvent,
                   max(e.occurredAt) as lastEvent
            from MailTrackingEvent e
            where e.delivery.trackingTokenHash is not null
            group by e.delivery.batch.id
            """)
    List<BatchEventAggregateView> eventAggregatesPerBatch();

    /**
     * Schlanke Projektion der Zustell-Zaehler eines Versandvorgangs. Enthaelt bewusst KEINEN Token/Hash.
     */
    interface BatchDeliveryCountsView {
        Long getBatchId();

        String getSubject();

        /** Anhang-Downloadname des Versandvorgangs; {@code null}, wenn ohne Anhang versendet. */
        String getAttachmentFilename();

        Instant getBatchCreatedAt();

        /** Gesamtzahl aller Zustellungen des Batches (ueber alle Status). */
        long getTotalDeliveries();

        long getSentCount();

        long getFailedCount();

        long getNotSentCount();
    }

    /**
     * Aggregierte Ereigniszahlen eines Versandvorgangs (nur fuer Batches mit mindestens einem Ereignis auf
     * einer getrackten Zustellung).
     */
    interface BatchEventAggregateView {
        Long getBatchId();

        /** Anzahl der getrackten Zustellungen des Batches mit mindestens einer Aktion. */
        long getRespondingRecipients();

        /** Gesamtzahl der Aktionen (Klickereignisse) ueber die getrackten Zustellungen des Batches. */
        long getTotalActions();

        Instant getFirstEvent();

        Instant getLastEvent();
    }
}
