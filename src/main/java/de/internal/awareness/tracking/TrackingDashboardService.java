package de.internal.awareness.tracking;

import de.internal.awareness.recipient.DeliveryStatus;
import de.internal.awareness.tracking.TrackingDashboardRepository.DeliveryClickAggregateView;
import de.internal.awareness.tracking.TrackingDashboardRepository.TrackedDeliveryView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Nur-Lese-Aggregationsdienst fuer das Admin-Tracking-Dashboard des globalen Mail-Composers.
 *
 * <p>"Getrackte" Zustellungen sind ausschliesslich {@code MailDelivery} mit gesetztem Trainingslink
 * ({@code tracking_token_hash IS NOT NULL}). Zustellungen ohne Trainingslink sind nicht trackbar und liegen
 * ausserhalb des Dashboard-Scopes.</p>
 *
 * <p><b>Kennzahlen (Summary)</b> sind stets GLOBAL ueber alle getrackten Zustellungen und damit unabhaengig vom
 * gesetzten {@link Filter}. Der Filter wirkt ausschliesslich auf die angezeigte Zeilenliste.</p>
 *
 * <p><b>Performance / N+1</b>: Der Dienst laedt die Daten mit genau ZWEI Abfragen (getrackte Zustellungen +
 * Klick-Aggregate je Zustellung, siehe {@link TrackingDashboardRepository}) und verknuepft sie im Speicher ueber
 * die Delivery-Id. Es wird keine Abfrage je Zustellung ausgefuehrt.</p>
 *
 * <p><b>Sicherheit/Datenschutz</b>: Weder Token noch Token-Hash, weder IP-Adressen, User-Agents noch
 * Fingerprints werden gelesen, in Records uebernommen oder geloggt. Ausgegeben werden nur die ohnehin bekannte
 * Empfaenger-Identitaet (Name/E-Mail), der Versandvorgang, der Anhangname sowie Zeitpunkte und Zaehler.</p>
 */
@Service
public class TrackingDashboardService {

    /**
     * Zeitzone fuer die (rein anzeigebezogene) Datumsangabe im Batch-Label. Bewusst fest verdrahtet
     * (Europe/Berlin), damit das Label unabhaengig von der Server-Standardzone stabil und reproduzierbar ist.
     */
    private static final ZoneId LABEL_ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter LABEL_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TrackingDashboardRepository repository;

    public TrackingDashboardService(TrackingDashboardRepository repository) {
        this.repository = repository;
    }

    /**
     * Eine Dashboard-Zeile: genau eine getrackte Zustellung inklusive ihrer Klick-Auswertung.
     *
     * @param deliveryId         technische Id der Zustellung
     * @param recipientName      Anzeigename des Empfaengers (kann {@code null} sein)
     * @param email              E-Mail-Adresse des Empfaengers
     * @param batchId            Id des Versandvorgangs
     * @param batchSubject       Betreff des Versandvorgangs
     * @param attachmentFilename Anhang-Downloadname ({@code null}, wenn ohne Anhang)
     * @param sentAt             Sendezeitpunkt ({@code null}, solange nicht erfolgreich versendet)
     * @param status             Versandstatus der Zustellung
     * @param triggered          ob der Trainingslink mindestens einmal ausgeloest wurde ({@code clickCount > 0})
     * @param clickCount         Anzahl der Klicks
     * @param firstClick         Zeitpunkt des ersten Klicks ({@code null}, falls keiner)
     * @param lastClick          Zeitpunkt des letzten Klicks ({@code null}, falls keiner)
     */
    public record Row(Long deliveryId, String recipientName, String email, Long batchId, String batchSubject,
                      String attachmentFilename, Instant sentAt, DeliveryStatus status,
                      boolean triggered, long clickCount, Instant firstClick, Instant lastClick) {
    }

    /**
     * Globale Kennzahlen ueber alle getrackten Zustellungen (unabhaengig vom Filter).
     *
     * @param totalTrackedDeliveries Anzahl aller getrackten Zustellungen
     * @param respondingRecipients   Anzahl getrackter Zustellungen mit mindestens einem Klick
     * @param totalClicks            Gesamtzahl aller Klickereignisse ueber getrackte Zustellungen
     */
    public record Summary(long totalTrackedDeliveries, long respondingRecipients, long totalClicks) {
    }

    /**
     * Eintrag fuer das Batch-Auswahlfeld (Dropdown) des Dashboards.
     *
     * @param id    Id des Versandvorgangs
     * @param label Anzeigebezeichnung, z. B. {@code "#12 - Rechnung September (2026-09-28)"}
     */
    public record BatchOption(Long id, String label) {
    }

    /**
     * Filterkriterien fuer die angezeigten Zeilen (nicht fuer die Kennzahlen).
     *
     * @param query          Freitext (Teilstring, case-insensitiv) fuer Anzeigename ODER E-Mail; {@code null},
     *                       wenn kein Freitextfilter (leere/blanke Eingaben werden zu {@code null} normalisiert)
     * @param batchId        Einschraenkung auf einen Versandvorgang; {@code null} = alle
     * @param onlyTriggered  {@code true} = nur Zeilen mit mindestens einem Klick
     */
    public record Filter(String query, Long batchId, boolean onlyTriggered) {

        /** Normalisiert den Freitext: trimmen, leere Eingabe zu {@code null}. */
        public Filter {
            if (query != null) {
                String trimmed = query.trim();
                query = trimmed.isEmpty() ? null : trimmed;
            }
        }

        /** Fabrikmethode mit Normalisierung des Freitextes (trim; leer/blank -&gt; {@code null}). */
        public static Filter of(String query, Long batchId, boolean onlyTriggered) {
            return new Filter(query, batchId, onlyTriggered);
        }

        /** Leerer Filter (keine Einschraenkung). */
        public static Filter none() {
            return new Filter(null, null, false);
        }
    }

    /**
     * Vollstaendige Sicht fuer das Dashboard: globale Kennzahlen, gefilterte Zeilen, Batch-Dropdown und der
     * angewandte (normalisierte) Filter.
     */
    public record DashboardView(Summary summary, List<Row> rows, List<BatchOption> batches, Filter filter) {
    }

    /**
     * Laedt die Dashboard-Sicht. Kennzahlen und Batch-Dropdown sind global (ueber alle getrackten Zustellungen);
     * die Zeilenliste wird gemaess {@code filter} eingeschraenkt und stabil sortiert.
     *
     * @param filter Filter; {@code null} wird als leerer Filter behandelt
     */
    @Transactional(readOnly = true)
    public DashboardView load(Filter filter) {
        Filter effective = (filter != null) ? filter : Filter.none();

        List<TrackedDeliveryView> tracked = repository.findTrackedDeliveries();

        // Klick-Aggregate je Zustellung (eine group-by-Abfrage) fuer den In-Memory-Join indexieren.
        Map<Long, DeliveryClickAggregateView> clicksByDelivery = new HashMap<>();
        for (DeliveryClickAggregateView agg : repository.aggregateClicksPerDelivery()) {
            clicksByDelivery.put(agg.getDeliveryId(), agg);
        }

        // Vollstaendige (ungefilterte) Zeilenliste - Grundlage sowohl fuer die globalen Kennzahlen als auch
        // (nach Filterung) fuer die Anzeige.
        List<Row> allRows = new ArrayList<>(tracked.size());
        for (TrackedDeliveryView d : tracked) {
            DeliveryClickAggregateView agg = clicksByDelivery.get(d.getDeliveryId());
            long clickCount = (agg != null) ? agg.getClickCount() : 0L;
            Instant firstClick = (agg != null) ? agg.getFirstClick() : null;
            Instant lastClick = (agg != null) ? agg.getLastClick() : null;
            allRows.add(new Row(
                    d.getDeliveryId(), d.getRecipientName(), d.getEmail(),
                    d.getBatchId(), d.getBatchSubject(), d.getAttachmentFilename(),
                    d.getSentAt(), d.getStatus(),
                    clickCount > 0L, clickCount, firstClick, lastClick));
        }

        Summary summary = summarize(allRows);
        List<BatchOption> batches = buildBatchOptions(tracked);

        List<Row> rows = new ArrayList<>();
        for (Row row : allRows) {
            if (matches(row, effective)) {
                rows.add(row);
            }
        }
        rows.sort(ROW_ORDER);

        return new DashboardView(summary, rows, batches, effective);
    }

    /** Globale Kennzahlen aus der vollstaendigen (ungefilterten) Zeilenliste ableiten. */
    private static Summary summarize(List<Row> allRows) {
        long total = allRows.size();
        long responding = 0L;
        long totalClicks = 0L;
        for (Row row : allRows) {
            if (row.clickCount() > 0L) {
                responding++;
            }
            totalClicks += row.clickCount();
        }
        return new Summary(total, responding, totalClicks);
    }

    /**
     * Batch-Dropdown: alle Versandvorgaenge mit mindestens einer getrackten Zustellung, dedupliziert und neueste
     * zuerst (nach {@code batchCreatedAt} absteigend, dann Id absteigend). Immer global - unabhaengig vom Filter.
     */
    private static List<BatchOption> buildBatchOptions(List<TrackedDeliveryView> tracked) {
        // Pro Batch nur einmal; die erste gesehene Zeile liefert Betreff/Erstellzeit (je Batch identisch).
        Map<Long, TrackedDeliveryView> byBatch = new HashMap<>();
        for (TrackedDeliveryView d : tracked) {
            byBatch.putIfAbsent(d.getBatchId(), d);
        }
        List<TrackedDeliveryView> distinct = new ArrayList<>(byBatch.values());
        distinct.sort(Comparator
                .comparing(TrackedDeliveryView::getBatchCreatedAt,
                        Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
                .thenComparing(TrackedDeliveryView::getBatchId,
                        Comparator.nullsLast(Comparator.<Long>reverseOrder())));
        List<BatchOption> options = new ArrayList<>(distinct.size());
        for (TrackedDeliveryView d : distinct) {
            options.add(new BatchOption(d.getBatchId(), batchLabel(d)));
        }
        return options;
    }

    /** Label-Format: {@code "#<id> - <subject> (<yyyy-MM-dd>)"} (Datum in Europe/Berlin). */
    private static String batchLabel(TrackedDeliveryView d) {
        String subject = (d.getBatchSubject() != null) ? d.getBatchSubject() : "";
        String date = (d.getBatchCreatedAt() != null)
                ? LABEL_DATE.format(LocalDate.ofInstant(d.getBatchCreatedAt(), LABEL_ZONE))
                : "";
        return "#" + d.getBatchId() + " - " + subject + " (" + date + ")";
    }

    /** Prueft, ob eine Zeile dem Filter entspricht (Freitext auf Name ODER E-Mail, Batch, only-triggered). */
    private static boolean matches(Row row, Filter filter) {
        if (filter.onlyTriggered() && row.clickCount() <= 0L) {
            return false;
        }
        if (filter.batchId() != null && !filter.batchId().equals(row.batchId())) {
            return false;
        }
        String query = filter.query();
        if (query != null) {
            String needle = query.toLowerCase(Locale.ROOT);
            String name = (row.recipientName() != null) ? row.recipientName().toLowerCase(Locale.ROOT) : "";
            String email = (row.email() != null) ? row.email().toLowerCase(Locale.ROOT) : "";
            if (!name.contains(needle) && !email.contains(needle)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Stabile, sinnvolle Sortierung der Zeilen: juengste Aktivitaet zuerst. Primaer nach letztem Klick
     * absteigend (Zeilen mit Klick zuerst; {@code null} = kein Klick zuletzt), dann nach Sendezeitpunkt
     * absteigend, dann nach Erstellzeitpunkt absteigend, schliesslich nach Delivery-Id absteigend als
     * deterministischer Tiebreaker.
     */
    private static final Comparator<Row> ROW_ORDER = Comparator
            .comparing(Row::lastClick, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(Row::sentAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(Row::deliveryId, Comparator.nullsLast(Comparator.<Long>reverseOrder()));
}
