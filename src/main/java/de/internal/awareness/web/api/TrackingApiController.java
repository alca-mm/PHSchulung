package de.internal.awareness.web.api;

import de.internal.awareness.recipient.DeliveryStatus;
import de.internal.awareness.tracking.TrackingBatchStatsService;
import de.internal.awareness.tracking.TrackingDashboardService;
import de.internal.awareness.tracking.TrackingDashboardService.Filter;
import de.internal.awareness.tracking.TrackingDashboardService.Reacted;
import de.internal.awareness.tracking.TrackingDashboardService.SortDir;
import de.internal.awareness.tracking.TrackingDashboardService.SortKey;
import de.internal.awareness.tracking.TrackingDeliveryService;
import de.internal.awareness.web.api.dto.ApiError;
import de.internal.awareness.web.api.dto.BatchStatDto;
import de.internal.awareness.web.api.dto.DashboardResponse;
import de.internal.awareness.web.api.dto.DeliveryDetailResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * JSON-API des Composer-Trackings fuer ein (spaeter statisches) Frontend. Spiegelt genau die Funktionalitaet der
 * serverseitigen Thymeleaf-Seiten ({@code TrackingDashboardController}, {@code TrackingDeliveryController},
 * {@code TrackingBatchStatsController}) als reine Nur-Lese-JSON-Endpunkte wider, ohne die bestehenden Seiten zu
 * veraendern.
 *
 * <p>Sicherheit/Datenschutz: Autorisierung uebernimmt ausschliesslich die globale Security-Filterkette
 * ({@code anyRequest().authenticated()}, siehe {@code SecurityConfig}); hier wird bewusst KEINE eigene
 * Security-Konfiguration ergaenzt. Es werden ausschliesslich explizite, nicht sensible DTOs serialisiert -
 * niemals JPA-Entities, Token, Token-Hashes, IP-Adressen, User-Agents oder sonstige Telemetrie.</p>
 *
 * <p>Rein lesend (nur {@code GET}); es gibt keine schreibenden Endpunkte, daher kein CSRF-Aspekt. Alle Filter-
 * und Sortierparameter werden - exakt wie im {@code TrackingDashboardController} - null- und fehlertolerant
 * geparst: ungueltige Status-, Reaktions-, Sortier- oder Datumsangaben werden auf einen sicheren Standard bzw.
 * auf "kein Filter" abgebildet und fuehren NIE zu einem HTTP 500.</p>
 *
 * <p>Zeitpunkte werden als rohe {@link Instant} ausgegeben (Jackson: ISO-8601, UTC); jegliche
 * zeitzonenbezogene (z. B. Europe/Berlin) Formatierung ist bewusst Aufgabe des Frontends.</p>
 */
@RestController
@RequestMapping("/api/tracking")
public class TrackingApiController {

    /** Eingabezeitzone fuer die Datumsfilter (fest verdrahtet, unabhaengig von der Serverzone) - wie die HTML-Seite. */
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private final TrackingDashboardService trackingDashboardService;
    private final TrackingDeliveryService trackingDeliveryService;
    private final TrackingBatchStatsService trackingBatchStatsService;

    public TrackingApiController(TrackingDashboardService trackingDashboardService,
                                TrackingDeliveryService trackingDeliveryService,
                                TrackingBatchStatsService trackingBatchStatsService) {
        this.trackingDashboardService = trackingDashboardService;
        this.trackingDeliveryService = trackingDeliveryService;
        this.trackingBatchStatsService = trackingBatchStatsService;
    }

    /**
     * Dashboard als JSON: globale Kennzahlen, gefilterte/sortierte Zeilen, Batch-Dropdown und der normalisierte
     * Filter. Alle Parameter sind optional und werden identisch zur Thymeleaf-Seite geparst.
     */
    @GetMapping
    public DashboardResponse dashboard(@RequestParam(name = "query", required = false) String query,
                                       @RequestParam(name = "batchId", required = false) Long batchId,
                                       @RequestParam(name = "fileName", required = false) String fileName,
                                       @RequestParam(name = "status", required = false) String status,
                                       @RequestParam(name = "reacted", required = false) String reacted,
                                       @RequestParam(name = "from", required = false) String from,
                                       @RequestParam(name = "to", required = false) String to,
                                       @RequestParam(name = "sort", required = false) String sort,
                                       @RequestParam(name = "dir", required = false) String dir) {

        DeliveryStatus statusValue = parseStatus(status);
        Reacted reactedValue = parseReacted(reacted);
        SortKey sortValue = parseSortKey(sort);
        SortDir dirValue = parseSortDir(dir);

        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);
        Instant fromInstant = (fromDate != null) ? fromDate.atStartOfDay(ZONE).toInstant() : null;
        Instant toInstant = (toDate != null) ? toDate.atTime(LocalTime.MAX).atZone(ZONE).toInstant() : null;

        Filter filter = Filter.of(
                query, batchId, fileName, statusValue, reactedValue, fromInstant, toInstant, sortValue, dirValue);
        TrackingDashboardService.DashboardView view = trackingDashboardService.load(filter);

        // Nur die gueltige ISO-Form (yyyy-MM-dd) fuer das Filter-Echo zurueckgeben (null, falls ungueltig).
        String fromEcho = (fromDate != null) ? fromDate.toString() : null;
        String toEcho = (toDate != null) ? toDate.toString() : null;
        return TrackingApiMapper.toDashboardResponse(view, fromEcho, toEcho);
    }

    /**
     * Detailsicht einer Zustellung als JSON. Existiert keine Zustellung mit dieser Id, wird eine kontrollierte
     * 404-JSON-Antwort ({@link ApiError}) zurueckgegeben - bewusst NICHT die HTML-Fehlerseite des globalen
     * {@code WebExceptionHandler} und ohne jegliche internen Details (kein Stacktrace, keine Pfade).
     */
    @GetMapping("/deliveries/{id}")
    public ResponseEntity<?> delivery(@PathVariable Long id) {
        return trackingDeliveryService.findDelivery(id)
                .<ResponseEntity<?>>map(detail ->
                        ResponseEntity.ok(TrackingApiMapper.toDeliveryDetailResponse(detail)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ApiError("not_found", "Zustellung nicht gefunden.")));
    }

    /** Batch-Auswertung als JSON (alle Versandvorgaenge mit mindestens einer Zustellung, neueste zuerst). */
    @GetMapping("/batches")
    public List<BatchStatDto> batches() {
        return TrackingApiMapper.toBatchStatDtos(trackingBatchStatsService.batchStats());
    }

    /** Parst den Versandstatus nullsicher (case-insensitiv); leer/ungueltig ergibt {@code null} (kein Filter). */
    private static DeliveryStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DeliveryStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Bildet den Reaktionsfilter ab; nur {@code REACTED}/{@code NOT_REACTED} greifen, alles andere -&gt; {@code ALL}. */
    private static Reacted parseReacted(String raw) {
        if (raw == null) {
            return Reacted.ALL;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "REACTED" -> Reacted.REACTED;
            case "NOT_REACTED" -> Reacted.NOT_REACTED;
            default -> Reacted.ALL;
        };
    }

    /** Parst den Sortierschluessel nullsicher; leer/ungueltig ergibt den Standard {@link SortKey#LAST_CLICK}. */
    private static SortKey parseSortKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return SortKey.LAST_CLICK;
        }
        try {
            return SortKey.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return SortKey.LAST_CLICK;
        }
    }

    /** Parst die Sortierrichtung nullsicher; leer/ungueltig ergibt den Standard {@link SortDir#DESC}. */
    private static SortDir parseSortDir(String raw) {
        if (raw == null || raw.isBlank()) {
            return SortDir.DESC;
        }
        try {
            return SortDir.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return SortDir.DESC;
        }
    }

    /** Parst ein ISO-Datum (yyyy-MM-dd) nullsicher; leer/ungueltig ergibt {@code null} (kein Zeitraumfilter). */
    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
