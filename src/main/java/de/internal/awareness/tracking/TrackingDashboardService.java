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
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Nur-Lese-Aggregationsdienst fuer das Admin-Tracking-Dashboard des globalen Mail-Composers.
 *
 * <p>"Getrackte" Zustellungen sind ausschliesslich {@code MailDelivery} mit gesetztem Trainingslink
 * ({@code tracking_token_hash IS NOT NULL}). Zustellungen ohne Trainingslink sind nicht trackbar und liegen
 * ausserhalb des Dashboard-Scopes.</p>
 *
 * <p><b>Kennzahlen (Summary)</b> sind stets GLOBAL ueber alle getrackten Zustellungen und damit unabhaengig vom
 * gesetzten {@link Filter}. Der Filter (und die Sortierung) wirken ausschliesslich auf die angezeigte
 * Zeilenliste.</p>
 *
 * <p><b>Performance / N+1</b>: Der Dienst laedt die Daten mit genau ZWEI Abfragen (getrackte Zustellungen +
 * Klick-Aggregate je Zustellung, siehe {@link TrackingDashboardRepository}) und verknuepft sie im Speicher ueber
 * die Delivery-Id. Filterung und Sortierung erfolgen rein im Speicher auf der bereits geladenen Zeilenliste; es
 * wird keine zusaetzliche Abfrage je Zustellung, Filter oder Sortierkriterium ausgefuehrt.</p>
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
     * <p>Wichtig zur Semantik: Jede getrackte Zustellung zaehlt als GENAU EIN "Empfaenger-Eintrag"
     * ({@code totalRecipients}). Wird derselbe Kontakt in mehreren Versandvorgaengen (oder mehrfach) beliefert,
     * erzeugt jede Zustellung einen eigenen Eintrag; {@code totalRecipients} ist also die Anzahl der getrackten
     * Zustellungen, nicht die Anzahl verschiedener Personen.</p>
     *
     * @param sentTrackedDeliveries   Anzahl getrackter Zustellungen mit Status {@link DeliveryStatus#SENT}
     * @param totalRecipients         Anzahl aller getrackten Zustellungen (je Zustellung ein Empfaenger-Eintrag)
     * @param respondingRecipients    Anzahl getrackter Zustellungen mit mindestens einem Klick
     *                                ({@code clickCount > 0})
     * @param nonRespondingRecipients {@code totalRecipients - respondingRecipients}
     * @param totalActions            Gesamtzahl aller Klickereignisse (Summe der {@code clickCount})
     * @param actionRate              Anteil reagierender Empfaenger:
     *                                {@code totalRecipients == 0 ? 0.0 : respondingRecipients / totalRecipients}
     * @param avgActionsPerResponder  durchschnittliche Klicks je reagierendem Empfaenger:
     *                                {@code respondingRecipients == 0 ? 0.0 : totalActions / respondingRecipients}
     */
    public record Summary(long sentTrackedDeliveries, long totalRecipients, long respondingRecipients,
                          long nonRespondingRecipients, long totalActions, double actionRate,
                          double avgActionsPerResponder) {
    }

    /**
     * Eintrag fuer das Batch-Auswahlfeld (Dropdown) des Dashboards.
     *
     * @param id    Id des Versandvorgangs
     * @param label Anzeigebezeichnung, z. B. {@code "#12 - Rechnung September (2026-09-28)"}
     */
    public record BatchOption(Long id, String label) {
    }

    /** Reaktions-Filter: alle Zeilen, nur reagierende oder nur nicht reagierende. */
    public enum Reacted {
        /** Keine Einschraenkung nach Reaktion. */
        ALL,
        /** Nur Zeilen mit mindestens einem Klick ({@code clickCount > 0}). */
        REACTED,
        /** Nur Zeilen ohne Klick ({@code clickCount == 0}). */
        NOT_REACTED
    }

    /** Sortierschluessel fuer die angezeigten Zeilen. */
    public enum SortKey {
        /** Nach Sendezeitpunkt ({@code sentAt}). */
        SENT_AT,
        /** Nach erstem Klick ({@code firstClick}). */
        FIRST_CLICK,
        /** Nach letztem Klick ({@code lastClick}). */
        LAST_CLICK,
        /** Nach Anzahl der Klicks ({@code clickCount}). */
        ACTIONS
    }

    /** Sortierrichtung. */
    public enum SortDir {
        /** Aufsteigend. */
        ASC,
        /** Absteigend. */
        DESC
    }

    /**
     * Filter- und Sortierkriterien fuer die angezeigten Zeilen (nicht fuer die Kennzahlen).
     *
     * <p>Datumsfilter ({@code from}/{@code to}): wirken auf {@code sentAt} und sind inklusiv. Sind BEIDE Grenzen
     * {@code null}, gibt es keine Datumseinschraenkung und Zeilen mit {@code sentAt == null} bleiben erhalten.
     * Ist mindestens EINE Grenze gesetzt, werden Zeilen mit {@code sentAt == null} AUSGESCHLOSSEN (ein noch nicht
     * versendeter Eintrag kann keinem Sendezeitraum zugeordnet werden).</p>
     *
     * @param query    Freitext (Teilstring, case-insensitiv) fuer Anzeigename ODER E-Mail; {@code null} = kein
     *                 Freitextfilter (leere/blanke Eingaben werden zu {@code null} normalisiert)
     * @param batchId  Einschraenkung auf einen Versandvorgang (exakt); {@code null} = alle
     * @param fileName Teilstring (case-insensitiv) auf den Anhang-Downloadnamen; {@code null} = keine
     *                 Einschraenkung. Eine Zeile ohne Anhang ({@code attachmentFilename == null}) passt NIE auf
     *                 einen nicht-{@code null}en {@code fileName}-Filter
     * @param status   exakter {@link DeliveryStatus}; {@code null} = alle Status
     * @param reacted  Reaktions-Filter (siehe {@link Reacted}); {@code null} wird zu {@link Reacted#ALL}
     * @param from     inklusive untere Grenze fuer {@code sentAt}; {@code null} = keine untere Grenze
     * @param to       inklusive obere Grenze fuer {@code sentAt}; {@code null} = keine obere Grenze
     * @param sort     Sortierschluessel; {@code null} wird zu {@link SortKey#LAST_CLICK}
     * @param dir      Sortierrichtung; {@code null} wird zu {@link SortDir#DESC}
     */
    public record Filter(String query, Long batchId, String fileName, DeliveryStatus status, Reacted reacted,
                         Instant from, Instant to, SortKey sort, SortDir dir) {

        /** Normalisiert Freitext/Dateiname (trim, leer/blank -&gt; {@code null}) und setzt Defaults fuer Enums. */
        public Filter {
            query = blankToNull(query);
            fileName = blankToNull(fileName);
            if (reacted == null) {
                reacted = Reacted.ALL;
            }
            if (sort == null) {
                sort = SortKey.LAST_CLICK;
            }
            if (dir == null) {
                dir = SortDir.DESC;
            }
        }

        private static String blankToNull(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }

        /** Fabrikmethode mit derselben Normalisierung/Default-Belegung wie der kanonische Konstruktor. */
        public static Filter of(String query, Long batchId, String fileName, DeliveryStatus status, Reacted reacted,
                                Instant from, Instant to, SortKey sort, SortDir dir) {
            return new Filter(query, batchId, fileName, status, reacted, from, to, sort, dir);
        }

        /** Leerer Filter (keine Einschraenkung), Standardsortierung {@code (LAST_CLICK, DESC)}. */
        public static Filter none() {
            return new Filter(null, null, null, null, Reacted.ALL, null, null, SortKey.LAST_CLICK, SortDir.DESC);
        }
    }

    /**
     * Vollstaendige Sicht fuer das Dashboard: globale Kennzahlen, gefilterte + sortierte Zeilen, Batch-Dropdown
     * und der angewandte (normalisierte) Filter.
     */
    public record DashboardView(Summary summary, List<Row> rows, List<BatchOption> batches, Filter filter) {
    }

    /**
     * Laedt die Dashboard-Sicht. Kennzahlen und Batch-Dropdown sind global (ueber alle getrackten Zustellungen);
     * die Zeilenliste wird gemaess {@code filter} eingeschraenkt und gemaess {@code (sort, dir)} sortiert.
     *
     * @param filter Filter; {@code null} wird als leerer Filter ({@link Filter#none()}) behandelt
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
        // (nach Filterung/Sortierung) fuer die Anzeige.
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
        rows.sort(rowOrder(effective));

        return new DashboardView(summary, rows, batches, effective);
    }

    /** Globale Kennzahlen aus der vollstaendigen (ungefilterten) Zeilenliste ableiten. */
    private static Summary summarize(List<Row> allRows) {
        long totalRecipients = allRows.size();
        long sentTracked = 0L;
        long responding = 0L;
        long totalActions = 0L;
        for (Row row : allRows) {
            if (row.status() == DeliveryStatus.SENT) {
                sentTracked++;
            }
            if (row.clickCount() > 0L) {
                responding++;
            }
            totalActions += row.clickCount();
        }
        long nonResponding = totalRecipients - responding;
        double actionRate = (totalRecipients == 0L) ? 0.0 : (double) responding / (double) totalRecipients;
        double avgActionsPerResponder = (responding == 0L) ? 0.0 : (double) totalActions / (double) responding;
        return new Summary(sentTracked, totalRecipients, responding, nonResponding, totalActions,
                actionRate, avgActionsPerResponder);
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

    /**
     * Prueft, ob eine Zeile allen aktiven Filterkriterien entspricht: Freitext (Name ODER E-Mail), Batch,
     * Dateiname (Teilstring auf Anhang), Status, Reaktion und Sende-Zeitraum ({@code from}/{@code to} auf
     * {@code sentAt}).
     */
    private static boolean matches(Row row, Filter filter) {
        String query = filter.query();
        if (query != null) {
            String needle = query.toLowerCase(Locale.ROOT);
            String name = (row.recipientName() != null) ? row.recipientName().toLowerCase(Locale.ROOT) : "";
            String email = (row.email() != null) ? row.email().toLowerCase(Locale.ROOT) : "";
            if (!name.contains(needle) && !email.contains(needle)) {
                return false;
            }
        }
        if (filter.batchId() != null && !filter.batchId().equals(row.batchId())) {
            return false;
        }
        String fileName = filter.fileName();
        if (fileName != null) {
            // Zeile ohne Anhang passt nie auf einen gesetzten Dateinamen-Filter.
            if (row.attachmentFilename() == null) {
                return false;
            }
            if (!row.attachmentFilename().toLowerCase(Locale.ROOT).contains(fileName.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        if (filter.status() != null && filter.status() != row.status()) {
            return false;
        }
        switch (filter.reacted()) {
            case REACTED -> {
                if (row.clickCount() <= 0L) {
                    return false;
                }
            }
            case NOT_REACTED -> {
                if (row.clickCount() > 0L) {
                    return false;
                }
            }
            case ALL -> {
                // keine Einschraenkung
            }
        }
        Instant from = filter.from();
        Instant to = filter.to();
        if (from != null || to != null) {
            Instant sentAt = row.sentAt();
            if (sentAt == null) {
                // Bei gesetzter Zeitgrenze werden nicht versendete Zeilen (sentAt == null) ausgeschlossen.
                return false;
            }
            if (from != null && sentAt.isBefore(from)) {
                return false;
            }
            if (to != null && sentAt.isAfter(to)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Baut den Zeilen-Comparator gemaess {@code (sort, dir)}. {@code null}-Werte des Sortierschluessels landen
     * IMMER am Ende (unabhaengig von der Richtung). Deterministischer End-Tiebreaker: Delivery-Id absteigend.
     * Die Standardsortierung {@code (LAST_CLICK, DESC)} reproduziert damit die bisherige Reihenfolge
     * "juengste Aktivitaet zuerst".
     */
    private static Comparator<Row> rowOrder(Filter filter) {
        SortDir dir = filter.dir();
        Comparator<Row> primary = switch (filter.sort()) {
            case SENT_AT -> instantOrder(Row::sentAt, dir);
            case FIRST_CLICK -> instantOrder(Row::firstClick, dir);
            case LAST_CLICK -> instantOrder(Row::lastClick, dir);
            case ACTIONS -> longOrder(Row::clickCount, dir);
        };
        // Stabiler, deterministischer End-Tiebreaker: immer Delivery-Id absteigend (nullsLast rein defensiv).
        Comparator<Row> tiebreaker = Comparator.comparing(Row::deliveryId,
                Comparator.nullsLast(Comparator.<Long>reverseOrder()));
        return primary.thenComparing(tiebreaker);
    }

    /** Comparator ueber einen {@link Instant}-Schluessel; {@code null} immer zuletzt, Richtung nur fuer Nicht-Nulls. */
    private static Comparator<Row> instantOrder(Function<Row, Instant> key, SortDir dir) {
        Comparator<Instant> valueOrder = (dir == SortDir.ASC)
                ? Comparator.<Instant>naturalOrder()
                : Comparator.<Instant>reverseOrder();
        return Comparator.comparing(key, Comparator.nullsLast(valueOrder));
    }

    /** Comparator ueber einen {@code long}-Schluessel (nie {@code null}); Richtung ueber {@code reversed()}. */
    private static Comparator<Row> longOrder(ToLongFunction<Row> key, SortDir dir) {
        Comparator<Row> asc = Comparator.comparingLong(key);
        return (dir == SortDir.ASC) ? asc : asc.reversed();
    }
}
