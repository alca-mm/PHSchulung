package de.internal.awareness.web.api;

import de.internal.awareness.tracking.TrackingBatchStatsService.BatchStat;
import de.internal.awareness.tracking.TrackingDashboardService.BatchOption;
import de.internal.awareness.tracking.TrackingDashboardService.DashboardView;
import de.internal.awareness.tracking.TrackingDashboardService.Filter;
import de.internal.awareness.tracking.TrackingDashboardService.Row;
import de.internal.awareness.tracking.TrackingDashboardService.Summary;
import de.internal.awareness.tracking.TrackingDeliveryService.DeliveryDetail;
import de.internal.awareness.tracking.TrackingDeliveryService.TimelineEntry;
import de.internal.awareness.web.api.dto.BatchOptionDto;
import de.internal.awareness.web.api.dto.BatchStatDto;
import de.internal.awareness.web.api.dto.DashboardFilterDto;
import de.internal.awareness.web.api.dto.DashboardResponse;
import de.internal.awareness.web.api.dto.DashboardRowDto;
import de.internal.awareness.web.api.dto.DashboardSummaryDto;
import de.internal.awareness.web.api.dto.DeliveryDetailResponse;
import de.internal.awareness.web.api.dto.TimelineEntryDto;

import java.util.ArrayList;
import java.util.List;

/**
 * Reine Abbildungslogik von den Nur-Lese-Service-Records auf die expliziten JSON-DTOs der Tracking-API.
 *
 * <p>Bewusst zustandslos (nur statische Methoden) und ohne jeglichen Zugriff auf JPA-Entities: Es werden
 * ausschliesslich die bereits aufbereiteten, nicht sensiblen Service-Records uebersetzt. Token, Token-Hashes,
 * IP-Adressen oder sonstige Telemetrie existieren in diesen Records nicht und werden daher auch nicht
 * ausgegeben. Zeitpunkte werden als rohe {@link java.time.Instant} durchgereicht (Jackson serialisiert diese als
 * ISO-8601); jegliche zeitzonenbezogene Formatierung ist Aufgabe des Frontends.</p>
 */
public final class TrackingApiMapper {

    private TrackingApiMapper() {
    }

    /**
     * Bildet die vollstaendige Dashboard-Sicht auf die JSON-Antwort ab.
     *
     * @param view    die vom Service gelieferte (normalisierte) Dashboard-Sicht
     * @param fromEcho die vom Aufrufer geparste, gueltige untere Datumsgrenze (yyyy-MM-dd) oder {@code null}
     * @param toEcho   die vom Aufrufer geparste, gueltige obere Datumsgrenze (yyyy-MM-dd) oder {@code null}
     */
    public static DashboardResponse toDashboardResponse(DashboardView view, String fromEcho, String toEcho) {
        DashboardSummaryDto summary = toSummaryDto(view.summary());

        List<DashboardRowDto> rows = new ArrayList<>(view.rows().size());
        for (Row row : view.rows()) {
            rows.add(toRowDto(row));
        }

        List<BatchOptionDto> batches = new ArrayList<>(view.batches().size());
        for (BatchOption option : view.batches()) {
            batches.add(new BatchOptionDto(option.id(), option.label()));
        }

        DashboardFilterDto filter = toFilterDto(view.filter(), fromEcho, toEcho);
        return new DashboardResponse(summary, rows, batches, filter);
    }

    /** Bildet die globalen Kennzahlen ab (Feldumbenennungen laut API-Kontrakt). */
    public static DashboardSummaryDto toSummaryDto(Summary s) {
        return new DashboardSummaryDto(
                s.totalRecipients(),
                s.sentTrackedDeliveries(),
                s.respondingRecipients(),
                s.nonRespondingRecipients(),
                s.totalActions(),
                s.actionRate(),
                s.avgActionsPerResponder());
    }

    /** Bildet eine Dashboard-Zeile ab (Status als Name; Zeitpunkte roh als Instant). */
    public static DashboardRowDto toRowDto(Row row) {
        return new DashboardRowDto(
                row.deliveryId(),
                row.recipientName(),
                row.email(),
                row.batchId(),
                row.batchSubject(),
                row.attachmentFilename(),
                row.status() != null ? row.status().name() : null,
                row.sentAt(),
                row.triggered(),
                row.firstClick(),
                row.lastClick(),
                row.clickCount());
    }

    /**
     * Bildet den normalisierten Filter auf sein Echo-DTO ab. Freitext/Dateiname/Batch/Status/Reaktion/Sortierung
     * stammen aus dem bereits normalisierten Service-Filter; die Datumsgrenzen werden als vom Aufrufer geparste
     * yyyy-MM-dd-Zeichenketten uebernommen (bzw. {@code null}).
     */
    public static DashboardFilterDto toFilterDto(Filter filter, String fromEcho, String toEcho) {
        return new DashboardFilterDto(
                filter.query(),
                filter.batchId(),
                filter.fileName(),
                filter.status() != null ? filter.status().name() : null,
                filter.reacted() != null ? filter.reacted().name() : null,
                fromEcho,
                toEcho,
                filter.sort() != null ? filter.sort().name() : null,
                filter.dir() != null ? filter.dir().name() : null);
    }

    /** Bildet die Detailsicht einer Zustellung auf die gegliederte JSON-Antwort ab. */
    public static DeliveryDetailResponse toDeliveryDetailResponse(DeliveryDetail detail) {
        DeliveryDetailResponse.Recipient recipient = new DeliveryDetailResponse.Recipient(
                detail.recipientName(), detail.email());

        DeliveryDetailResponse.Delivery delivery = new DeliveryDetailResponse.Delivery(
                detail.deliveryId(),
                detail.batchId(),
                detail.batchSubject(),
                detail.attachmentFilename(),
                detail.status() != null ? detail.status().name() : null,
                detail.sentAt(),
                detail.attemptCount());

        DeliveryDetailResponse.Tracking tracking = new DeliveryDetailResponse.Tracking(
                detail.triggered(),
                detail.firstAction(),
                detail.lastAction(),
                detail.actionCount());

        List<TimelineEntryDto> timeline = new ArrayList<>(detail.timeline().size());
        for (TimelineEntry entry : detail.timeline()) {
            timeline.add(new TimelineEntryDto(
                    entry.type() != null ? entry.type().name() : null,
                    entry.occurredAt()));
        }

        return new DeliveryDetailResponse(recipient, delivery, tracking, timeline);
    }

    /** Bildet die Batch-Auswertung ab (Feldumbenennung {@code respondingRecipients -> reactedRecipients}). */
    public static List<BatchStatDto> toBatchStatDtos(List<BatchStat> stats) {
        List<BatchStatDto> result = new ArrayList<>(stats.size());
        for (BatchStat stat : stats) {
            result.add(new BatchStatDto(
                    stat.batchId(),
                    stat.subject(),
                    stat.attachmentFilename(),
                    stat.createdAt(),
                    stat.recipientCount(),
                    stat.sentCount(),
                    stat.failedCount(),
                    stat.notSentCount(),
                    stat.respondingRecipients(),
                    stat.actionRate(),
                    stat.totalActions(),
                    stat.firstEvent(),
                    stat.lastEvent()));
        }
        return result;
    }
}
