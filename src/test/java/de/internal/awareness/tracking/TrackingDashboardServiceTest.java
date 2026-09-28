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
import de.internal.awareness.tracking.TrackingDashboardService.Row;
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
 * {@link TrackingDashboardService} gegen die reale (isolierte) SQLite-Test-DB (wie {@code GeneratedFileServiceTest}:
 * {@code @SpringBootTest(NONE)} + {@code @Transactional} mit Rollback je Testmethode). Die Testdaten werden ueber
 * die bestehenden Repositories/Entities aufgebaut (Batch, Contact, getrackte {@link MailDelivery} mit gesetztem
 * {@code trackingTokenHash}, {@link MailTrackingEvent}).
 *
 * <p>Abgedeckt: globale Kennzahlen (inkl. Ausschluss einer UNGETRACKTEN Zustellung), erster/letzter Klick,
 * triggered-Flag, Filter (Name/E-Mail case-insensitiv Teilstring, kein Treffer, batchId, onlyTriggered), globale
 * Kennzahlen trotz gefilterter Zeilen, Batch-Dropdown (Inhalt/Reihenfolge, Ausschluss ungetrackter Batches),
 * Zeilensortierung und - sicherheitskritisch - dass KEIN Token-Hash in einer {@link Row} auftaucht.</p>
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

    private MailBatch batch1;   // "Rechnung September" - aeltere, drei getrackte Zustellungen
    private MailBatch batch2;   // "Passwort zuruecksetzen" - neuere, eine getrackte Zustellung
    private MailBatch batch3;   // "Nur ungetrackt" - nur eine UNGETRACKTE Zustellung (darf nirgends erscheinen)

    private Long d1Id;  // Alice, batch1, 2 Klicks
    private Long d2Id;  // Bob, batch1, 1 Klick
    private Long d3Id;  // Carol, batch1, 0 Klicks, FAILED
    private Long d4Id;  // Dave, batch2, 3 Klicks

    private String hash1;
    private String hash2;
    private String hash3;
    private String hash4;
    private String hashUntracked; // wird bewusst NICHT gesetzt (untracked); nur zur Vollstaendigkeit null

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
        hashUntracked = null;

        MailDelivery d1 = newDelivery(batch1, "Alice Anderson", "alice@example.invalid", hash1);
        d1.recordSent(now);
        MailDelivery d2 = newDelivery(batch1, "Bob Baker", "bob@example.invalid", hash2);
        d2.recordSent(now);
        MailDelivery d3 = newDelivery(batch1, "Carol Clark", "carol@example.invalid", hash3);
        d3.recordFailure("SEND"); // FAILED, sentAt bleibt null, keine Klicks
        MailDelivery d4 = newDelivery(batch2, "Dave Dunn", "dave@example.invalid", hash4);
        d4.recordSent(now);
        // UNGETRACKTE Zustellung (kein Trainingslink) - muss aus Scope/Summary/Batches ausgeschlossen sein.
        MailDelivery d5 = newDelivery(batch3, "Eve Evans", "eve@example.invalid", hashUntracked);
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

    // --- Kennzahlen (global, Ausschluss ungetrackter Zustellungen) ---

    @Test
    void summaryCountsGlobalOverTrackedDeliveriesExcludingUntracked() {
        DashboardView view = service.load(Filter.none());

        Summary summary = view.summary();
        assertThat(summary.totalTrackedDeliveries()).isEqualTo(4L); // d1..d4, ohne die ungetrackte d5
        assertThat(summary.respondingRecipients()).isEqualTo(3L);   // d1, d2, d4 (d3 = 0 Klicks)
        assertThat(summary.totalClicks()).isEqualTo(6L);            // 2 + 1 + 0 + 3

        // Die ungetrackte Zustellung/Adresse taucht in keiner Zeile auf.
        assertThat(view.rows()).hasSize(4);
        assertThat(view.rows()).extracting(Row::email).doesNotContain("eve@example.invalid");
    }

    // --- Erster/letzter Klick + triggered-Flag ---

    @Test
    void firstAndLastClickAndTriggeredFlagAreCorrect() {
        DashboardView view = service.load(Filter.none());

        Row alice = rowById(view, d1Id);
        assertThat(alice.clickCount()).isEqualTo(2L);
        assertThat(alice.triggered()).isTrue();
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
        DashboardView lower = service.load(Filter.of("anders", null, false));
        assertThat(lower.rows()).extracting(Row::deliveryId).containsExactly(d1Id);

        DashboardView upper = service.load(Filter.of("ANDERSON", null, false));
        assertThat(upper.rows()).extracting(Row::deliveryId).containsExactly(d1Id);
    }

    @Test
    void filterByQueryMatchesEmail() {
        DashboardView view = service.load(Filter.of("BOB@example", null, false));
        assertThat(view.rows()).extracting(Row::deliveryId).containsExactly(d2Id);
    }

    @Test
    void filterByQueryNoMatchReturnsEmptyRowsButGlobalSummary() {
        DashboardView view = service.load(Filter.of("zzz-kein-treffer", null, false));

        assertThat(view.rows()).isEmpty();
        // Summary bleibt global.
        assertThat(view.summary()).isEqualTo(new Summary(4L, 3L, 6L));
    }

    // --- Filter: batchId ---

    @Test
    void filterByBatchIdRestrictsRowsOnly() {
        DashboardView view = service.load(Filter.of(null, batch2.getId(), false));

        assertThat(view.rows()).extracting(Row::deliveryId).containsExactly(d4Id);
        // Summary bleibt global (unabhaengig vom Batchfilter).
        assertThat(view.summary()).isEqualTo(new Summary(4L, 3L, 6L));
    }

    // --- Filter: onlyTriggered ---

    @Test
    void filterOnlyTriggeredExcludesRowsWithoutClicks() {
        DashboardView view = service.load(Filter.of(null, null, true));

        assertThat(view.rows()).extracting(Row::deliveryId)
                .containsExactlyInAnyOrder(d1Id, d2Id, d4Id) // d3 (0 Klicks) ausgeschlossen
                .doesNotContain(d3Id);
        assertThat(view.rows()).allMatch(Row::triggered);
        assertThat(view.summary()).isEqualTo(new Summary(4L, 3L, 6L));
    }

    // --- Summary bleibt global waehrend Zeilen gefiltert werden (kombinierter Filter) ---

    @Test
    void summaryStaysGlobalWhileRowsAreFiltered() {
        DashboardView view = service.load(Filter.of("dave", batch2.getId(), true));

        assertThat(view.rows()).extracting(Row::deliveryId).containsExactly(d4Id);
        assertThat(view.summary().totalTrackedDeliveries()).isEqualTo(4L);
        assertThat(view.summary().respondingRecipients()).isEqualTo(3L);
        assertThat(view.summary().totalClicks()).isEqualTo(6L);
    }

    // --- Batch-Dropdown: nur Batches mit getrackter Zustellung, neueste zuerst ---

    @Test
    void batchesContainOnlyBatchesWithTrackedDeliveriesNewestFirst() {
        DashboardView view = service.load(Filter.none());

        // batch3 ("Nur ungetrackt") hat KEINE getrackte Zustellung -> nicht im Dropdown.
        assertThat(view.batches()).extracting(BatchOption::id)
                .containsExactly(batch2.getId(), batch1.getId())
                .doesNotContain(batch3.getId());

        // Label-Format: "#<id> - <subject> (<datum>)".
        BatchOption first = view.batches().get(0);
        assertThat(first.label()).startsWith("#" + batch2.getId() + " - Passwort zuruecksetzen (");
        assertThat(view.batches().get(1).label()).startsWith("#" + batch1.getId() + " - Rechnung September (");
    }

    // --- Zeilensortierung: juengste Aktivitaet zuerst ---

    @Test
    void rowsOrderedByMostRecentActivityDesc() {
        DashboardView view = service.load(Filter.none());

        // lastClick desc: d4 (now-5) > d2 (now-20) > d1 (now-35) > d3 (kein Klick -> zuletzt).
        assertThat(view.rows()).extracting(Row::deliveryId)
                .containsExactly(d4Id, d2Id, d1Id, d3Id);
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

    // --- Filter.of / Filter-Normalisierung ---

    @Test
    void filterOfNormalizesBlankQueryAndTrims() {
        assertThat(Filter.of("   ", null, false).query()).isNull();
        assertThat(Filter.of(null, null, false).query()).isNull();
        assertThat(Filter.of("  alice  ", 7L, true).query()).isEqualTo("alice");
        assertThat(Filter.of("  alice  ", 7L, true).batchId()).isEqualTo(7L);
        assertThat(Filter.of("  alice  ", 7L, true).onlyTriggered()).isTrue();
        // Auch der kanonische Konstruktor normalisiert (defensiv).
        assertThat(new Filter("  bob  ", null, false).query()).isEqualTo("bob");
    }
}
