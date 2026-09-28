package de.internal.awareness.web;

import de.internal.awareness.recipient.DeliveryStatus;
import de.internal.awareness.tracking.TrackingDashboardService;
import de.internal.awareness.tracking.TrackingDashboardService.Reacted;
import de.internal.awareness.tracking.TrackingDashboardService.SortDir;
import de.internal.awareness.tracking.TrackingDashboardService.SortKey;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Serverseitige Admin-Auswertungsseite ("Tracking-Dashboard") ueber ALLE Versandvorgaenge hinweg: je
 * Zustellung wird angezeigt, ob der eindeutig zugeordnete, serverseitig registrierte Trainingslink geklickt
 * wurde, samt Anzahl der Aktionen sowie erstem/letztem Klick. Ein Filter (Freitext Name/E-Mail, Versandvorgang,
 * Dateiname, Versandstatus, Reaktion, Zeitraum) schraenkt die Tabelle ein; die Kennzahlen (KPIs) bleiben stets
 * global. Zusaetzlich sind die aktivitaetsbezogenen Spalten sortierbar.
 *
 * <p>Sicherheit/Datenschutz: Diese Seite ist ausschliesslich fuer angemeldete Admins gedacht und bereits durch
 * die globale Regel {@code anyRequest().authenticated()} (siehe {@code SecurityConfig}) geschuetzt - es ist
 * bewusst KEINE eigene Security-Konfiguration noetig. Die Seite gibt NIEMALS Tokens, Token-Hashes oder sonstige
 * Geheimnisse aus, sondern nur bereits bekannte Empfaenger-Identitaeten (Name/E-Mail) sowie Versandvorgang,
 * Datei, Zeitpunkt und aggregierte Aktionszahlen. Es werden bewusst KEINE IP-Adressen, User-Agents oder sonstige
 * Telemetrie erfasst oder angezeigt (Datensparsamkeit).</p>
 *
 * <p>Rein lesend (nur {@code GET /tracking}); es gibt keine schreibenden Endpunkte, daher ist hier auch kein
 * CSRF-Aspekt zu beachten. Alle Filter- und Sortierkriterien werden ausschliesslich ueber Query-Parameter
 * uebergeben und robust (fehlertolerant) geparst: ungueltige Datums-, Status-, Reaktions- oder Sortierangaben
 * werden auf einen sicheren Standard bzw. auf "kein Filter" abgebildet und fuehren NIE zu einem HTTP 500.</p>
 */
@Controller
public class TrackingDashboardController {

    /** Anzeige-/Eingabezeitzone fuer die Datumsfilter (bewusst fest verdrahtet, unabhaengig von der Serverzone). */
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private final TrackingDashboardService trackingDashboardService;

    public TrackingDashboardController(TrackingDashboardService trackingDashboardService) {
        this.trackingDashboardService = trackingDashboardService;
    }

    /**
     * Zeigt das Tracking-Dashboard. Alle Parameter sind optional. Der Versandstatus wird nullsicher aus dem
     * String geparst (ungueltig/leer -&gt; kein Statusfilter). Die Zeitraumgrenzen {@code from}/{@code to} sind
     * Datumsangaben (yyyy-MM-dd) und werden in {@code Europe/Berlin} in {@link Instant} umgerechnet: {@code from}
     * auf den Tagesbeginn (00:00), {@code to} inklusiv auf das Tagesende (23:59:59.999999999). Ungueltige
     * Datumsangaben werden ignoriert (kein 500). Die Kennzahlen (KPIs) bleiben global; der Filter wirkt nur auf
     * die Tabellenzeilen. Es werden ausschliesslich nicht sensible Anzeige-Daten in das Modell gelegt (keine
     * Tokens/Hashes/Secrets). Zusaetzlich werden die (normalisierten) Filtereingaben als Strings zurueckgegeben,
     * damit das Formular seine Werte exakt wieder anzeigen kann.
     */
    @GetMapping("/tracking")
    public String dashboard(@RequestParam(name = "query", required = false) String query,
                            @RequestParam(name = "batchId", required = false) Long batchId,
                            @RequestParam(name = "fileName", required = false) String fileName,
                            @RequestParam(name = "status", required = false) String status,
                            @RequestParam(name = "reacted", required = false) String reacted,
                            @RequestParam(name = "from", required = false) String from,
                            @RequestParam(name = "to", required = false) String to,
                            @RequestParam(name = "sort", required = false) String sort,
                            @RequestParam(name = "dir", required = false) String dir,
                            Model model) {

        DeliveryStatus statusValue = parseStatus(status);
        Reacted reactedValue = parseReacted(reacted);
        SortKey sortValue = parseSortKey(sort);
        SortDir dirValue = parseSortDir(dir);

        // Datumsgrenzen robust parsen; ungueltige Eingaben -> null (kein Filter, kein 500). Fuer die
        // Formular-Wiederanzeige nur die gueltige ISO-Form (yyyy-MM-dd) zurueckgeben.
        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);
        Instant fromInstant = (fromDate != null)
                ? fromDate.atStartOfDay(ZONE).toInstant() : null;
        Instant toInstant = (toDate != null)
                ? toDate.atTime(LocalTime.MAX).atZone(ZONE).toInstant() : null;

        TrackingDashboardService.Filter filter = TrackingDashboardService.Filter.of(
                query, batchId, fileName, statusValue, reactedValue, fromInstant, toInstant, sortValue, dirValue);
        TrackingDashboardService.DashboardView view = trackingDashboardService.load(filter);

        model.addAttribute("view", view);
        model.addAttribute("summary", view.summary());
        model.addAttribute("rows", view.rows());
        model.addAttribute("batches", view.batches());
        model.addAttribute("filter", view.filter());

        // Rohwerte (normalisiert) fuer die exakte Formular-Wiederanzeige und fuer die Sortier-/Filter-Links.
        model.addAttribute("query", view.filter().query() != null ? view.filter().query() : "");
        model.addAttribute("fileName", view.filter().fileName() != null ? view.filter().fileName() : "");
        model.addAttribute("status", statusValue != null ? statusValue.name() : "");
        model.addAttribute("reacted", reactedValue.name());
        model.addAttribute("from", fromDate != null ? fromDate.toString() : "");
        model.addAttribute("to", toDate != null ? toDate.toString() : "");
        model.addAttribute("sort", sortValue.name());
        model.addAttribute("dir", dirValue.name());

        return "tracking/dashboard";
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
