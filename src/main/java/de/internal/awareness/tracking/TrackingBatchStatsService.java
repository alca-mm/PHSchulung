package de.internal.awareness.tracking;

import de.internal.awareness.tracking.TrackingBatchStatsRepository.BatchDeliveryCountsView;
import de.internal.awareness.tracking.TrackingBatchStatsRepository.BatchEventAggregateView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Nur-Lese-Aggregationsdienst fuer die Admin-Batch-Auswertung des globalen Mail-Composers: je
 * Versandvorgang ({@code MailBatch}) werden Zustell- und Ereigniszahlen zu einer kompakten Kennzahlzeile
 * ({@link BatchStat}) verdichtet.
 *
 * <p><b>Kennzahl-Semantik</b>: {@link BatchStat#recipientCount()} zaehlt ALLE Zustellungen des Batches (ueber
 * alle Status, unabhaengig davon, ob ein Trainingslink gesetzt ist). Demgegenueber stammen
 * {@link BatchStat#respondingRecipients()} und {@link BatchStat#totalActions()} ausschliesslich aus den
 * GETRACKTEN Zustellungen (Trainingslink gesetzt, {@code tracking_token_hash IS NOT NULL}) und deren
 * registrierten Ereignissen. Ein Batch ohne getrackte Zustellungen bzw. ohne Ereignisse hat daher 0 Responder
 * / 0 Aktionen und {@code null} als erster/letzter Ereigniszeitpunkt, obwohl {@code recipientCount > 0} sein
 * kann.</p>
 *
 * <p><b>Aktionsquote</b>: {@link BatchStat#actionRate()} = {@code recipientCount == 0 ? 0.0 :
 * (double) respondingRecipients / recipientCount}. Sie bezieht die (aus getrackten Zustellungen stammenden)
 * Responder auf die Gesamtzahl aller Zustellungen des Batches.</p>
 *
 * <p><b>Performance / N+1</b>: Der Dienst laedt die Daten mit genau ZWEI Abfragen (Zustell-Zaehler je Batch +
 * Ereignis-Aggregate je Batch, siehe {@link TrackingBatchStatsRepository}) und verknuepft sie im Speicher ueber
 * die Batch-Id. Es wird keine Abfrage je Batch ausgefuehrt.</p>
 *
 * <p><b>Sicherheit/Datenschutz</b>: Weder Token noch Token-Hash, weder IP-Adressen, User-Agents noch
 * Fingerprints werden gelesen, in Records uebernommen oder geloggt. Ausgegeben werden nur bekannte
 * Batch-Metadaten (Betreff, Anhangname, Erstellzeit) sowie Zaehler und Ereigniszeitpunkte.</p>
 */
@Service
public class TrackingBatchStatsService {

    private final TrackingBatchStatsRepository repository;

    public TrackingBatchStatsService(TrackingBatchStatsRepository repository) {
        this.repository = repository;
    }

    /**
     * Eine Auswertungszeile je Versandvorgang.
     *
     * @param batchId              technische Id des Versandvorgangs
     * @param subject              Betreff des Versandvorgangs
     * @param attachmentFilename   Anhang-Downloadname ({@code null}, wenn ohne Anhang versendet)
     * @param createdAt            Erstellzeitpunkt des Versandvorgangs
     * @param recipientCount       Anzahl ALLER Zustellungen des Batches (ueber alle Status)
     * @param sentCount            Anzahl erfolgreich versendeter Zustellungen ({@code SENT})
     * @param failedCount          Anzahl fehlgeschlagener Zustellungen ({@code FAILED})
     * @param notSentCount         Anzahl noch nicht (bzw. bewusst nicht) versendeter Zustellungen ({@code NOT_SENT})
     * @param respondingRecipients Anzahl getrackter Zustellungen mit mindestens einer Aktion
     * @param actionRate           Aktionsquote {@code respondingRecipients / recipientCount} (0.0, falls
     *                             {@code recipientCount == 0})
     * @param totalActions         Gesamtzahl der Aktionen (Klickereignisse) ueber die getrackten Zustellungen
     * @param firstEvent           Zeitpunkt der ersten Aktion ({@code null}, falls keine)
     * @param lastEvent            Zeitpunkt der letzten Aktion ({@code null}, falls keine)
     */
    public record BatchStat(Long batchId, String subject, String attachmentFilename, Instant createdAt,
                            long recipientCount, long sentCount, long failedCount, long notSentCount,
                            long respondingRecipients, double actionRate, long totalActions,
                            Instant firstEvent, Instant lastEvent) {
    }

    /**
     * Liefert die Batch-Auswertung ueber alle Versandvorgaenge mit mindestens einer Zustellung, neueste zuerst
     * (Erstellzeitpunkt absteigend, dann Batch-Id absteigend als deterministischer Tiebreaker).
     *
     * <p>Es werden genau zwei Aggregat-Abfragen ausgefuehrt und im Speicher ueber die Batch-Id verknuepft
     * (kein N+1). {@code recipientCount} zaehlt alle Zustellungen des Batches; Responder und Aktionen stammen
     * ausschliesslich aus den getrackten Zustellungen (siehe Klassen-Javadoc).</p>
     */
    @Transactional(readOnly = true)
    public List<BatchStat> batchStats() {
        List<BatchDeliveryCountsView> counts = repository.deliveryCountsPerBatch();

        // Ereignis-Aggregate je Batch (eine group-by-Abfrage) fuer den In-Memory-Join indexieren.
        Map<Long, BatchEventAggregateView> eventsByBatch = new HashMap<>();
        for (BatchEventAggregateView agg : repository.eventAggregatesPerBatch()) {
            eventsByBatch.put(agg.getBatchId(), agg);
        }

        List<BatchStat> stats = new ArrayList<>(counts.size());
        for (BatchDeliveryCountsView c : counts) {
            BatchEventAggregateView agg = eventsByBatch.get(c.getBatchId());
            long respondingRecipients = (agg != null) ? agg.getRespondingRecipients() : 0L;
            long totalActions = (agg != null) ? agg.getTotalActions() : 0L;
            Instant firstEvent = (agg != null) ? agg.getFirstEvent() : null;
            Instant lastEvent = (agg != null) ? agg.getLastEvent() : null;

            long recipientCount = c.getTotalDeliveries();
            double actionRate = (recipientCount == 0L) ? 0.0 : (double) respondingRecipients / recipientCount;

            stats.add(new BatchStat(
                    c.getBatchId(), c.getSubject(), c.getAttachmentFilename(), c.getBatchCreatedAt(),
                    recipientCount, c.getSentCount(), c.getFailedCount(), c.getNotSentCount(),
                    respondingRecipients, actionRate, totalActions,
                    firstEvent, lastEvent));
        }

        stats.sort(STAT_ORDER);
        return stats;
    }

    /**
     * Sortierung: neueste zuerst. Primaer nach Erstellzeitpunkt absteigend, dann nach Batch-Id absteigend als
     * deterministischer Tiebreaker (auch bei gleichem Zeitstempel stabil).
     */
    private static final Comparator<BatchStat> STAT_ORDER = Comparator
            .comparing(BatchStat::createdAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(BatchStat::batchId, Comparator.nullsLast(Comparator.<Long>reverseOrder()));
}
