package de.internal.awareness.tracking;

import de.internal.awareness.contact.Contact;
import de.internal.awareness.contact.ContactRepository;
import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailBatchRepository;
import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.mail.MailDeliveryRepository;
import de.internal.awareness.recipient.DeliveryStatus;
import de.internal.awareness.tracking.TrackingDashboardService.BatchOption;
import de.internal.awareness.tracking.TrackingDashboardService.DashboardView;
import de.internal.awareness.tracking.TrackingDashboardService.Filter;
import de.internal.awareness.tracking.TrackingDashboardService.Reacted;
import de.internal.awareness.tracking.TrackingDashboardService.Row;
import de.internal.awareness.tracking.TrackingDashboardService.SortDir;
import de.internal.awareness.tracking.TrackingDashboardService.SortKey;
import de.internal.awareness.tracking.TrackingDashboardService.Summary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TrackingDashboardService} gegen die reale (isolierte) SQLite-Test-DB ({@code @SpringBootTest(NONE)} +
 * {@code @Transactional} mit Rollback je Testmethode). Die Testdaten werden ueber die bestehenden
 * Repositories/Entities aufgebaut (Batch, Contact, getrackte {@link MailDelivery} mit gesetztem
 * {@code trackingTokenHash}, {@link MailTrackingEvent}).
 *
 * <p>Abgedeckt: alle sieben KPIs der {@link Summary} (inkl. {@code actionRate}/{@code avgActionsPerResponder}
 * samt Division-durch-Null =&gt; {@code 0.0}), Aggregation mehrerer Klicks je Zustellung, jeder neue Filter
 * (Freitext Name/E-Mail, batchId, Dateiname-Teilstring inkl. Ausschluss bei fehlendem Anhang, Status, Reaktion,
 * Datum {@code from}/{@code to} mit inklusiven Grenzen und Ausschluss von {@code sentAt == null} bei gesetzter
 * Grenze), jeder Sortierschluessel in beiden Richtungen mit "nulls last", die Standardsortierung
 * ("juengste Aktivitaet zuerst"), der deterministische Tiebreaker (Delivery-Id absteigend), global bleibende
 * Kennzahlen trotz gefilterter Zeilen sowie - sicherheitskritisch - dass KEIN Token-Hash in einer {@link Row}
 * auftaucht.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class TrackingDashboardServiceTest {

    @TempDir
    static Path generatedDir;

    @DynamicPropertySource
    static void fileProps(DynamicPropertyRegistry registry) {
        // Datenverzeichnis auf ein Temp-Verzeichnis umbiegen: der Kontext (inkl. GeneratedFileService) darf
        // niemals das reale ./data-Verzeichnis beruehren.
        registry.add("app.files.generated-dir", () -> generatedDir.toString());
    }

    @Autowired
    private TrackingDashboardService service;

    @Autowired
    private MailBatchRepository batchRepository;

    @Autowired
    private MailDeliveryRepository deliveryRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private MailTrackingEventRepository eventRepository;

    // Fixe Zeitachse (auf Millisekunden gekuerzt, damit SQLite-Rueckgaben exakt vergleichbar sind).
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private MailBatch batch1;   // "Rechnung September" - aeltere, drei getrackte Zustellungen, Anhang "rechnung.docx"
    private MailBatch batch2;   // "Passwort zuruecksetzen" - neuere, eine getrackte Zustellung, KEIN Anhang
    private MailBatch batch3;   // "Nur ungetrackt" - nur eine UNGETRACKTE Zustellung (darf nirgends erscheinen)

    private Long d1Id;  // Alice, batch1, SENT (now-50), 2 Klicks (first now-40, last now-35)
    private Long d2Id;  // Bob,   batch1, SENT (now-30), 1 Klick  (now-20)
    private Long d3Id;  // Carol, batch1, FAILED (sentAt null), 0 Klicks
    private Long d4Id;  // Dave,  batch2, SENT (now-10), 3 Klicks (now-10, now-8, now-5)

    private String hash1;
    private String hash2;
    private String hash3;
    private String hash4;

    @BeforeEach
    void setUp() {
        batch1 = batchRepository.saveAndFlush(new MailBatch(
                "Rechnung September", "Hallo", "training@example.invalid", null, null, "rechnung.docx", 3));
        batch2 = batchRepository.saveAndFlush(new MailBatch(
                "Passwort zuruecksetzen", "Hallo", "training@example.invalid", null, null, null, 1));
        batch3 = batchRepository.saveAndFlush(new MailBatch(
                "Nur ungetrackt", "Hallo", "training@example.invalid", null, null, null, 1));

        hash1 = TrackingTokens.generate().tokenHash();
        hash2 = TrackingTokens.generate().tokenHash();
        hash3 = TrackingTokens.generate().tokenHash();
        hash4 = TrackingTokens.generate().tokenHash();

        MailDelivery d1 = newDelivery(batch1, "Alice Anderson", "alice@example.invalid", hash1);
        d1.recordSent(now.minus(50, ChronoUnit.MINUTES));
        MailDelivery d2 = newDelivery(batch1, "Bob Baker", "bob@example.invalid", hash2);
        d2.recordSent(now.minus(30, ChronoUnit.MINUTES));
        MailDelivery d3 = newDelivery(batch1, "Carol Clark", "carol@example.invalid", hash3);
        d3.recordFailure("SEND"); // FAILED, sentAt bleibt null, keine Klicks
        MailDelivery d4 = newDelivery(batch2, "Dave Dunn", "dave@example.invalid", hash4);
        d4.recordSent(now.minus(10, ChronoUnit.MINUTES));
        // UNGETRACKTE Zustellung (kein Trainingslink) - muss aus Scope/Summary/Batches ausgeschlossen sein.
        MailDelivery d5 = newDelivery(batch3, "Eve Evans", "eve@example.invalid", null);
        d5.recordSent(now);

        deliveryRepository.saveAndFlush(d1);
        deliveryRepository.saveAndFlush(d2);
        deliveryRepository.saveAndFlush(d3);
        deliveryRepository.saveAndFlush(d4);
        deliveryRepository.saveAndFlush(d5);

        d1Id = d1.getId();
        d2Id = d2.getId();
        d3Id = d3.getId();
        d4Id = d4.getId();

        // Klicks: d1 -> 2 (first now-40, last now-35), d2 -> 1 (now-20), d4 -> 3 (now-10, now-8, now-5).
        click(d1, now.minus(40, ChronoUnit.MINUTES));
        click(d1, now.minus(35, ChronoUnit.MINUTES));
        click(d2, now.minus(20, ChronoUnit.MINUTES));
        click(d4, now.minus(10, ChronoUnit.MINUTES));
        click(d4, now.minus(8, ChronoUnit.MINUTES));
        click(d4, now.minus(5, ChronoUnit.MINUTES));
        eventRepository.flush();
    }

    private MailDelivery newDelivery(MailBatch batch, String name, String email, String hash) {
        Contact contact = contactRepository.saveAndFlush(new Contact(email, name));
        return new MailDelivery(batch, contact, hash);
    }

    private void click(MailDelivery delivery, Instant when) {
        eventRepository.save(new MailTrackingEvent(delivery, TrackingEventType.LINK_CLICK, when));
    }

    private Row rowById(DashboardView view, Long deliveryId) {
        return view.rows().stream().filter(r -> r.deliveryId().equals(deliveryId)).findFirst().orElseThrow();
    }

    private static Filter sortFilter(SortKey sort, SortDir dir) {
        return Filter.of(null, null, null, null, null, null, null, sort, dir);
    }

    private static Filter dateFilter(Instant from, Instant to) {
        return Filter.of(null, null, null, null, null, from, to, null, null);
    }

    // --- Kennzahlen: alle sieben KPIs, global ueber getrackte Zustellungen (Ausschluss ungetrackter) ---

    @Test
    void summaryComputesAllSevenKpisGloballyOverTrackedDeliveries() {
        DashboardView view = service.load(Filter.none());
        Summary s = view.summary();

        assertThat(s.sentTrackedDeliveries()).isEqualTo(3L);   // d1, d2, d4 SENT; d3 FAILED
        assertThat(s.totalRecipients()).isEqualTo(4L);         // d1..d4 (ungetrackte d5 ausgeschlossen)
        assertThat(s.respondingRecipients()).isEqualTo(3L);    // d1, d2, d4 (d3 = 0 Klicks)
        assertThat(s.nonRespondingRecipients()).isEqualTo(1L); // d3
        assertThat(s.totalActions()).isEqualTo(6L);            // 2 + 1 + 0 + 3
        assertThat(s.actionRate()).isEqualTo(0.75);            // 3 / 4
        assertThat(s.avgActionsPerResponder()).isEqualTo(2.0); // 6 / 3

        // Die ungetrackte Zustellung/Adresse taucht in keiner Zeile auf.
        assertThat(view.rows()).hasSize(4);
        assertThat(view.rows()).extracting(Row::email).doesNotContain("eve@example.invalid");
    }

    @Test
    void actionRateAndAvgAreZeroWhenNobodyResponds() {
        // Alle Klicks entfernen -> getrackte Zustellungen bleiben (4), aber keine Reaktion.
        eventRepository.deleteAll();
        eventRepository.flush();

        Summary s = service.load(Filter.none()).summary();
        assertThat(s.totalRecipients()).isEqualTo(4L);
        assertThat(s.sentTrackedDeliveries()).isEqualTo(3L);
        assertThat(s.respondingRecipients()).isZero();
        assertThat(s.nonRespondingRecipients()).isEqualTo(4L);
        assertThat(s.totalActions()).isZero();
        assertThat(s.actionRate()).isEqualTo(0.0);            // 0 / 4
        assertThat(s.avgActionsPerResponder()).isEqualTo(0.0); // responders == 0 -> 0.0 (keine Division)
    }

    @Test
    void allKpisAreZeroWhenNoTrackedDeliveries() {
        // FK-Reihenfolge: erst Events, dann Deliveries loeschen -> gar keine getrackte Zustellung mehr.
        eventRepository.deleteAll();
        eventRepository.flush();
        deliveryRepository.deleteAll();
        deliveryRepository.flush();

        DashboardView view = service.load(Filter.none());
        Summary s = view.summary();
        assertThat(s.sentTrackedDeliveries()).isZero();
        assertThat(s.totalRecipients()).isZero();
        assertThat(s.respondingRecipients()).isZero();
        assertThat(s.nonRespondingRecipients()).isZero();
        assertThat(s.totalActions()).isZero();
        assertThat(s.actionRate()).isEqualTo(0.0);             // totalRecipients == 0 -> 0.0 (keine Division)
        assertThat(s.avgActionsPerResponder()).isEqualTo(0.0);
        assertThat(view.rows()).isEmpty();
    }

    // --- Aggregation mehrerer Klicks je Zustellung (clickCount, first/last, triggered) ---

    @Test
    void multipleClicksAreAggregatedPerDelivery() {
        DashboardView view = service.load(Filter.none());

        Row dave = rowById(view, d4Id);
        assertThat(dave.clickCount()).isEqualTo(3L);
        assertThat(dave.triggered()).isTrue();
        assertThat(dave.firstClick()).isEqualTo(now.minus(10, ChronoUnit.MINUTES));
        assertThat(dave.lastClick()).isEqualTo(now.minus(5, ChronoUnit.MINUTES));

        Row alice = rowById(view, d1Id);
        assertThat(alice.clickCount()).isEqualTo(2L);
        assertThat(alice.firstClick()).isEqualTo(now.minus(40, ChronoUnit.MINUTES));
        assertThat(alice.lastClick()).isEqualTo(now.minus(35, ChronoUnit.MINUTES));

        Row carol = rowById(view, d3Id);
        assertThat(carol.clickCount()).isZero();
        assertThat(carol.triggered()).isFalse();
        assertThat(carol.firstClick()).isNull();
        assertThat(carol.lastClick()).isNull();
        assertThat(carol.status()).isEqualTo(DeliveryStatus.FAILED);
    }

    // --- Filter: Freitext (Name/E-Mail, case-insensitiv, Teilstring) ---

    @Test
    void filterByQueryMatchesRecipientNameCaseInsensitiveSubstring() {
        // Bewusst "anders" statt z. B. "ali": alle E-Mails enden auf "@example.invalid", und "invalid" enthaelt
        // die Teilzeichenkette "ali" - "anders" kommt hingegen nur im Namen "Alice Anderson" vor.
        assertThat(service.load(Filter.of("anders", null, null, null, null, null, null, null, null)).rows())
                .extracting(Row::deliveryId).containsExactly(d1Id);
        assertThat(service.load(Filter.of("ANDERSON", null, null, null, null, null, null, null, null)).rows())
                .extracting(Row::deliveryId).containsExactly(d1Id);
    }

    @Test
    void filterByQueryMatchesEmailCaseInsensitive() {
        assertThat(service.load(Filter.of("BOB@example", null, null, null, null, null, null, null, null)).rows())
                .extracting(Row::deliveryId).containsExactly(d2Id);
    }

    @Test
    void filterByQueryNoMatchReturnsEmptyRowsButGlobalSummary() {
        DashboardView view = service.load(
                Filter.of("zzz-kein-treffer", null, null, null, null, null, null, null, null));

        assertThat(view.rows()).isEmpty();
        // Summary bleibt global.
        assertThat(view.summary()).isEqualTo(new Summary(3L, 4L, 3L, 1L, 6L, 0.75, 2.0));
    }

    // --- Filter: batchId ---

    @Test
    void filterByBatchIdRestrictsRowsOnly() {
        DashboardView view = service.load(
                Filter.of(null, batch2.getId(), null, null, null, null, null, null, null));

        assertThat(view.rows()).extracting(Row::deliveryId).containsExactly(d4Id);
        // Summary bleibt global (unabhaengig vom Batchfilter).
        assertThat(view.summary()).isEqualTo(new Summary(3L, 4L, 3L, 1L, 6L, 0.75, 2.0));
    }

    // --- Filter: Dateiname (Teilstring case-insensitiv) inkl. Ausschluss bei fehlendem Anhang ---

    @Test
    void filterByFileNameSubstringCaseInsensitiveExcludesNullAttachment() {
        // batch1-Zeilen (d1, d2, d3) haben Anhang "rechnung.docx"; d4 (batch2) hat KEINEN Anhang.
        assertThat(service.load(Filter.of(null, null, "rechnung", null, null, null, null, null, null)).rows())
                .extracting(Row::deliveryId).containsExactlyInAnyOrder(d1Id, d2Id, d3Id)
                .doesNotContain(d4Id);
        // Case-insensitiv, Teilstring auf die Endung.
        assertThat(service.load(Filter.of(null, null, ".DOCX", null, null, null, null, null, null)).rows())
                .extracting(Row::deliveryId).containsExactlyInAnyOrder(d1Id, d2Id, d3Id);
        // Zeile ohne Anhang matcht NIE einen gesetzten Dateinamen-Filter (auch nicht bei sehr generischem Text).
        assertThat(service.load(Filter.of(null, null, "x", null, null, null, null, null, null)).rows())
                .extracting(Row::deliveryId).doesNotContain(d4Id);
    }

    // --- Filter: Status (exakt) ---

    @Test
    void filterByStatusExactMatch() {
        assertThat(service.load(
                Filter.of(null, null, null, DeliveryStatus.SENT, null, null, null, null, null)).rows())
                .extracting(Row::deliveryId).containsExactlyInAnyOrder(d1Id, d2Id, d4Id);
        assertThat(service.load(
                Filter.of(null, null, null, DeliveryStatus.FAILED, null, null, null, null, null)).rows())
                .extracting(Row::deliveryId).containsExactly(d3Id);
    }

    // --- Filter: Reaktion (REACTED / NOT_REACTED / ALL) ---

    @Test
    void filterByReactedVariants() {
        assertThat(service.load(
                Filter.of(null, null, null, null, Reacted.REACTED, null, null, null, null)).rows())
                .extracting(Row::deliveryId).containsExactlyInAnyOrder(d1Id, d2Id, d4Id)
                .doesNotContain(d3Id);
        assertThat(service.load(
                Filter.of(null, null, null, null, Reacted.NOT_REACTED, null, null, null, null)).rows())
                .extracting(Row::deliveryId).containsExactly(d3Id);
        assertThat(service.load(
                Filter.of(null, null, null, null, Reacted.ALL, null, null, null, null)).rows())
                .extracting(Row::deliveryId).containsExactlyInAnyOrder(d1Id, d2Id, d3Id, d4Id);
    }

    // --- Filter: Datum (from/to auf sentAt), inklusive Grenzen, Ausschluss von sentAt == null bei Grenze ---

    @Test
    void filterByDateFromInclusiveLowerBoundExcludesNullSentAt() {
        // from == d2.sentAt (now-30): >= now-30 behalten -> d2 (Gleichheit inklusiv) und d4 (now-10).
        DashboardView view = dateFilterView(now.minus(30, ChronoUnit.MINUTES), null);
        assertThat(view.rows()).extracting(Row::deliveryId).containsExactlyInAnyOrder(d2Id, d4Id)
                .doesNotContain(d1Id)   // now-50 liegt vor der Grenze
                .doesNotContain(d3Id);  // sentAt == null bei gesetzter Grenze ausgeschlossen
    }

    @Test
    void filterByDateToInclusiveUpperBoundExcludesNullSentAt() {
        // to == d2.sentAt (now-30): <= now-30 behalten -> d1 (now-50) und d2 (Gleichheit inklusiv).
        DashboardView view = dateFilterView(null, now.minus(30, ChronoUnit.MINUTES));
        assertThat(view.rows()).extracting(Row::deliveryId).containsExactlyInAnyOrder(d1Id, d2Id)
                .doesNotContain(d4Id)   // now-10 liegt nach der Grenze
                .doesNotContain(d3Id);  // sentAt == null bei gesetzter Grenze ausgeschlossen
    }

    @Test
    void filterByDateRangeBothBoundsInclusive() {
        // Enges Fenster genau auf d2.sentAt: from == to == now-30 -> nur d2 (beide Grenzen inklusiv).
        DashboardView view = dateFilterView(now.minus(30, ChronoUnit.MINUTES), now.minus(30, ChronoUnit.MINUTES));
        assertThat(view.rows()).extracting(Row::deliveryId).containsExactly(d2Id);
    }

    @Test
    void noDateBoundsKeepsRowsWithNullSentAt() {
        // Beide Grenzen null -> keine Datumseinschraenkung; d3 (sentAt == null) bleibt enthalten.
        DashboardView view = service.load(dateFilter(null, null));
        assertThat(view.rows()).extracting(Row::deliveryId).contains(d3Id);
        assertThat(view.rows()).hasSize(4);
    }

    private DashboardView dateFilterView(Instant from, Instant to) {
        return service.load(dateFilter(from, to));
    }

    // --- Summary bleibt global waehrend Zeilen (kombiniert) gefiltert werden ---

    @Test
    void summaryStaysGlobalWhileRowsAreFiltered() {
        DashboardView view = service.load(Filter.of(
                "dave", batch2.getId(), null, DeliveryStatus.SENT, Reacted.REACTED, null, null, null, null));

        assertThat(view.rows()).extracting(Row::deliveryId).containsExactly(d4Id);
        assertThat(view.summary()).isEqualTo(new Summary(3L, 4L, 3L, 1L, 6L, 0.75, 2.0));
    }

    // --- Batch-Dropdown: nur Batches mit getrackter Zustellung, neueste zuerst (unveraendert) ---

    @Test
    void batchesContainOnlyBatchesWithTrackedDeliveriesNewestFirst() {
        DashboardView view = service.load(Filter.none());

        // batch3 ("Nur ungetrackt") hat KEINE getrackte Zustellung -> nicht im Dropdown.
        assertThat(view.batches()).extracting(BatchOption::id)
                .containsExactly(batch2.getId(), batch1.getId())
                .doesNotContain(batch3.getId());

        BatchOption first = view.batches().get(0);
        assertThat(first.label()).startsWith("#" + batch2.getId() + " - Passwort zuruecksetzen (");
        assertThat(view.batches().get(1).label()).startsWith("#" + batch1.getId() + " - Rechnung September (");
    }

    // --- Sortierung: jeder Schluessel in beiden Richtungen, nulls immer zuletzt ---

    @Test
    void sortBySentAtBothDirectionsNullsLast() {
        // sentAt: d1=now-50, d2=now-30, d4=now-10, d3=null
        assertThat(service.load(sortFilter(SortKey.SENT_AT, SortDir.ASC)).rows())
                .extracting(Row::deliveryId).containsExactly(d1Id, d2Id, d4Id, d3Id);
        assertThat(service.load(sortFilter(SortKey.SENT_AT, SortDir.DESC)).rows())
                .extracting(Row::deliveryId).containsExactly(d4Id, d2Id, d1Id, d3Id);
    }

    @Test
    void sortByFirstClickBothDirectionsNullsLast() {
        // firstClick: d1=now-40, d2=now-20, d4=now-10, d3=null
        assertThat(service.load(sortFilter(SortKey.FIRST_CLICK, SortDir.ASC)).rows())
                .extracting(Row::deliveryId).containsExactly(d1Id, d2Id, d4Id, d3Id);
        assertThat(service.load(sortFilter(SortKey.FIRST_CLICK, SortDir.DESC)).rows())
                .extracting(Row::deliveryId).containsExactly(d4Id, d2Id, d1Id, d3Id);
    }

    @Test
    void sortByLastClickBothDirectionsNullsLast() {
        // lastClick: d1=now-35, d2=now-20, d4=now-5, d3=null
        assertThat(service.load(sortFilter(SortKey.LAST_CLICK, SortDir.ASC)).rows())
                .extracting(Row::deliveryId).containsExactly(d1Id, d2Id, d4Id, d3Id);
        assertThat(service.load(sortFilter(SortKey.LAST_CLICK, SortDir.DESC)).rows())
                .extracting(Row::deliveryId).containsExactly(d4Id, d2Id, d1Id, d3Id);
    }

    @Test
    void sortByActionsBothDirections() {
        // clickCount: d3=0, d2=1, d1=2, d4=3 (0 ist ein echter Wert, kein null -> keine Sonderbehandlung).
        assertThat(service.load(sortFilter(SortKey.ACTIONS, SortDir.ASC)).rows())
                .extracting(Row::deliveryId).containsExactly(d3Id, d2Id, d1Id, d4Id);
        assertThat(service.load(sortFilter(SortKey.ACTIONS, SortDir.DESC)).rows())
                .extracting(Row::deliveryId).containsExactly(d4Id, d1Id, d2Id, d3Id);
    }

    @Test
    void defaultSortIsNewestActivityFirst() {
        // Standard (LAST_CLICK, DESC): d4(now-5) > d2(now-20) > d1(now-35) > d3(kein Klick -> zuletzt).
        assertThat(service.load(Filter.none()).rows())
                .extracting(Row::deliveryId).containsExactly(d4Id, d2Id, d1Id, d3Id);
    }

    @Test
    void sortTiebreakerIsDeliveryIdDescendingForEqualKeys() {
        // Zwei zusaetzliche getrackte Zustellungen OHNE Klick -> lastClick == null (Gleichstand mit d3).
        MailDelivery x = newDelivery(batch2, "X Null", "x@example.invalid", TrackingTokens.generate().tokenHash());
        x.recordSent(now);
        MailDelivery y = newDelivery(batch2, "Y Null", "y@example.invalid", TrackingTokens.generate().tokenHash());
        y.recordSent(now);
        deliveryRepository.saveAndFlush(x);
        deliveryRepository.saveAndFlush(y);
        Long xId = x.getId();
        Long yId = y.getId(); // yId > xId (spaeter angelegt)

        // (LAST_CLICK, DESC): erst die drei Klick-Zeilen (d4, d2, d1), dann die null-Gruppe {d3, x, y} -
        // "nulls last", und unter Gleichstand Delivery-Id absteigend -> y, x, d3.
        assertThat(service.load(sortFilter(SortKey.LAST_CLICK, SortDir.DESC)).rows())
                .extracting(Row::deliveryId)
                .containsExactly(d4Id, d2Id, d1Id, yId, xId, d3Id);
    }

    // --- Filter.of / none() / Normalisierung + Defaults ---

    @Test
    void filterNormalizesBlanksAndAppliesEnumDefaults() {
        Filter blank = Filter.of("  ", null, "  ", null, null, null, null, null, null);
        assertThat(blank.query()).isNull();
        assertThat(blank.fileName()).isNull();
        assertThat(blank.reacted()).isEqualTo(Reacted.ALL);
        assertThat(blank.sort()).isEqualTo(SortKey.LAST_CLICK);
        assertThat(blank.dir()).isEqualTo(SortDir.DESC);

        Filter full = Filter.of("  alice  ", 7L, "  Rechnung.docx  ", DeliveryStatus.SENT, Reacted.REACTED,
                now.minus(1, ChronoUnit.HOURS), now, SortKey.ACTIONS, SortDir.ASC);
        assertThat(full.query()).isEqualTo("alice");
        assertThat(full.fileName()).isEqualTo("Rechnung.docx");
        assertThat(full.batchId()).isEqualTo(7L);
        assertThat(full.status()).isEqualTo(DeliveryStatus.SENT);
        assertThat(full.reacted()).isEqualTo(Reacted.REACTED);
        assertThat(full.sort()).isEqualTo(SortKey.ACTIONS);
        assertThat(full.dir()).isEqualTo(SortDir.ASC);

        // Auch der kanonische Konstruktor normalisiert/defaulted (defensiv).
        assertThat(new Filter("  bob  ", null, null, null, null, null, null, null, null).query()).isEqualTo("bob");
        assertThat(new Filter(null, null, null, null, null, null, null, null, null).sort())
                .isEqualTo(SortKey.LAST_CLICK);

        Filter none = Filter.none();
        assertThat(none).isEqualTo(new Filter(
                null, null, null, null, Reacted.ALL, null, null, SortKey.LAST_CLICK, SortDir.DESC));
    }

    @Test
    void loadNullFilterBehavesAsNone() {
        DashboardView view = service.load(null);
        assertThat(view.filter()).isEqualTo(Filter.none());
        assertThat(view.rows()).hasSize(4);
    }

    // --- Sicherheit: KEIN Token-Hash in einer Row (Reflection ueber Record-Komponenten + toString) ---

    @Test
    void noTokenHashLeaksIntoAnyRow() {
        DashboardView view = service.load(Filter.none());
        Set<String> secrets = Set.of(hash1, hash2, hash3, hash4);

        assertThat(view.rows()).isNotEmpty();
        RecordComponent[] components = Row.class.getRecordComponents();
        List<String> offending = new ArrayList<>();
        for (Row row : view.rows()) {
            // 1) toString der gesamten Zeile darf keinen Hash enthalten.
            String asString = row.toString();
            for (String secret : secrets) {
                if (asString.contains(secret)) {
                    offending.add("toString enthaelt Hash");
                }
            }
            // 2) Kein einzelnes Feld ist gleich einem bekannten Hash.
            for (RecordComponent component : components) {
                Object value;
                try {
                    value = component.getAccessor().invoke(row);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("Zugriff auf Row-Komponente fehlgeschlagen: " + component.getName(), e);
                }
                if (value != null && secrets.contains(value.toString())) {
                    offending.add(component.getName());
                }
            }
        }
        assertThat(offending).as("Row-Felder/-toString duerfen keinen Token-Hash enthalten").isEmpty();

        // Zusatz: die bekannten Hashes sind echte 64-stellige Hex-Werte (also nicht versehentlich "leer").
        assertThat(secrets).allMatch(h -> h != null && h.matches("[0-9a-f]{64}"));
    }
}
