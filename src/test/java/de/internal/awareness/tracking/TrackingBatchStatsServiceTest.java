package de.internal.awareness.tracking;

import de.internal.awareness.contact.Contact;
import de.internal.awareness.contact.ContactRepository;
import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailBatchRepository;
import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.mail.MailDeliveryRepository;
import de.internal.awareness.recipient.DeliveryStatus;
import de.internal.awareness.tracking.TrackingBatchStatsService.BatchStat;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link TrackingBatchStatsService} gegen die reale (isolierte) SQLite-Test-DB (wie
 * {@code TrackingDashboardServiceTest}: {@code @SpringBootTest(NONE)} + {@code @Transactional} mit Rollback je
 * Testmethode). Die Testdaten werden ueber die bestehenden Repositories/Entities aufgebaut.
 *
 * <p>Abgedeckt: per-Batch-Zaehler ueber ALLE Zustellungen ({@code SENT}/{@code FAILED}/{@code NOT_SENT}),
 * Responder/Aktionen ausschliesslich aus getrackten Zustellungen mit Events, erster/letzter Event, Aktionsquote
 * ({@code respondingRecipients / recipientCount}), Ausschluss von Batches ohne Zustellung, Sortierung (neueste
 * zuerst) sowie - sicherheitskritisch - dass KEIN Token-Hash in einer {@link BatchStat} auftaucht.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class TrackingBatchStatsServiceTest {

    @TempDir
    static Path generatedDir;

    @DynamicPropertySource
    static void fileProps(DynamicPropertyRegistry registry) {
        registry.add("app.files.generated-dir", () -> generatedDir.toString());
    }

    @Autowired
    private TrackingBatchStatsService service;

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

    private MailBatch batchA;  // aeltester: 3 Zustellungen gemischt, 1 getrackter Responder mit 2 Events
    private MailBatch batchB;  // mittlerer: 1 getrackte Zustellung OHNE Event
    private MailBatch batchC;  // neuester: 2 UNGETRACKTE Zustellungen (nie Responder)

    private String hashA1;
    private String hashA2;
    private String hashB1;

    @BeforeEach
    void setUp() {
        batchA = batchRepository.saveAndFlush(new MailBatch(
                "A-Batch", "Text", "training@example.invalid", "IT Security", null, "a.docx", 99));
        batchB = batchRepository.saveAndFlush(new MailBatch(
                "B-Batch", "Text", "training@example.invalid", "IT Security", null, null, 88));
        batchC = batchRepository.saveAndFlush(new MailBatch(
                "C-Batch", "Text", "training@example.invalid", "IT Security", null, "c.pdf", 77));

        hashA1 = TrackingTokens.generate().tokenHash();
        hashA2 = TrackingTokens.generate().tokenHash();
        hashB1 = TrackingTokens.generate().tokenHash();

        // Batch A: A1 SENT (getrackt, 2 Events), A2 FAILED (getrackt, 0 Events), A3 NOT_SENT (ungetrackt).
        MailDelivery a1 = newDelivery(batchA, "Alice", "alice@example.invalid", hashA1);
        a1.recordSent(now);
        MailDelivery a2 = newDelivery(batchA, "Bob", "bob@example.invalid", hashA2);
        a2.recordFailure("SEND");
        MailDelivery a3 = newDelivery(batchA, "Carol", "carol@example.invalid", null); // ungetrackt, NOT_SENT
        deliveryRepository.saveAndFlush(a1);
        deliveryRepository.saveAndFlush(a2);
        deliveryRepository.saveAndFlush(a3);
        click(a1, now.minus(40, ChronoUnit.MINUTES)); // erster Event des Batches
        click(a1, now.minus(30, ChronoUnit.MINUTES)); // letzter Event des Batches
        eventRepository.flush();

        // Batch B: B1 SENT (getrackt, KEIN Event).
        MailDelivery b1 = newDelivery(batchB, "Dave", "dave@example.invalid", hashB1);
        b1.recordSent(now);
        deliveryRepository.saveAndFlush(b1);

        // Batch C: C1 SENT ungetrackt, C2 NOT_SENT ungetrackt.
        MailDelivery c1 = newDelivery(batchC, "Eve", "eve@example.invalid", null);
        c1.recordSent(now);
        MailDelivery c2 = newDelivery(batchC, "Frank", "frank@example.invalid", null);
        deliveryRepository.saveAndFlush(c1);
        deliveryRepository.saveAndFlush(c2);
    }

    private MailDelivery newDelivery(MailBatch batch, String name, String email, String hash) {
        Contact contact = contactRepository.saveAndFlush(new Contact(email, name));
        return new MailDelivery(batch, contact, hash);
    }

    private void click(MailDelivery delivery, Instant when) {
        eventRepository.save(new MailTrackingEvent(delivery, TrackingEventType.LINK_CLICK, when));
    }

    private BatchStat statById(List<BatchStat> stats, Long batchId) {
        return stats.stream().filter(s -> s.batchId().equals(batchId)).findFirst().orElseThrow();
    }

    @Test
    void deliveryCountsCoverAllStatusesPerBatch() {
        List<BatchStat> stats = service.batchStats();

        BatchStat a = statById(stats, batchA.getId());
        // recipientCount zaehlt ALLE Zustellungen (3), NICHT das Feld recipientCount (99).
        assertThat(a.recipientCount()).isEqualTo(3L);
        assertThat(a.sentCount()).isEqualTo(1L);
        assertThat(a.failedCount()).isEqualTo(1L);
        assertThat(a.notSentCount()).isEqualTo(1L);

        BatchStat c = statById(stats, batchC.getId());
        assertThat(c.recipientCount()).isEqualTo(2L);
        assertThat(c.sentCount()).isEqualTo(1L);
        assertThat(c.failedCount()).isEqualTo(0L);
        assertThat(c.notSentCount()).isEqualTo(1L);
    }

    @Test
    void respondersAndActionsComeFromTrackedDeliveriesOnly() {
        List<BatchStat> stats = service.batchStats();

        BatchStat a = statById(stats, batchA.getId());
        // Nur A1 hat Events (2 Stueck); A2 ist getrackt aber ohne Event, A3 ist ungetrackt.
        assertThat(a.respondingRecipients()).isEqualTo(1L);
        assertThat(a.totalActions()).isEqualTo(2L);
        assertThat(a.firstEvent()).isEqualTo(now.minus(40, ChronoUnit.MINUTES));
        assertThat(a.lastEvent()).isEqualTo(now.minus(30, ChronoUnit.MINUTES));
        // Aktionsquote = respondingRecipients / recipientCount = 1/3.
        assertThat(a.actionRate()).isCloseTo(1.0 / 3.0, within(1e-9));
    }

    @Test
    void trackedBatchWithoutEventsHasZeroRespondersAndNullTimes() {
        List<BatchStat> stats = service.batchStats();

        BatchStat b = statById(stats, batchB.getId());
        assertThat(b.recipientCount()).isEqualTo(1L);
        assertThat(b.sentCount()).isEqualTo(1L);
        assertThat(b.respondingRecipients()).isEqualTo(0L);
        assertThat(b.totalActions()).isEqualTo(0L);
        assertThat(b.firstEvent()).isNull();
        assertThat(b.lastEvent()).isNull();
        assertThat(b.actionRate()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void untrackedOnlyBatchHasZeroRespondersButCountsDeliveries() {
        List<BatchStat> stats = service.batchStats();

        BatchStat c = statById(stats, batchC.getId());
        assertThat(c.recipientCount()).isEqualTo(2L);
        assertThat(c.respondingRecipients()).isEqualTo(0L);
        assertThat(c.totalActions()).isEqualTo(0L);
        assertThat(c.firstEvent()).isNull();
        assertThat(c.lastEvent()).isNull();
        assertThat(c.actionRate()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void batchesWithAtLeastOneDeliveryAreAllPresentNewestFirst() {
        List<BatchStat> stats = service.batchStats();

        // Alle drei Batches haben mindestens eine Zustellung -> alle vorhanden, neueste zuerst (C, B, A).
        assertThat(stats).extracting(BatchStat::batchId)
                .containsExactly(batchC.getId(), batchB.getId(), batchA.getId());
    }

    @Test
    void noTokenHashLeaksIntoAnyBatchStat() {
        List<BatchStat> stats = service.batchStats();
        Set<String> secrets = Set.of(hashA1, hashA2, hashB1);

        assertThat(stats).isNotEmpty();
        RecordComponent[] components = BatchStat.class.getRecordComponents();
        for (BatchStat stat : stats) {
            String asString = stat.toString();
            for (String secret : secrets) {
                assertThat(asString).doesNotContain(secret);
            }
            for (RecordComponent component : components) {
                Object value;
                try {
                    value = component.getAccessor().invoke(stat);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("Zugriff auf BatchStat-Komponente fehlgeschlagen: "
                            + component.getName(), e);
                }
                if (value != null) {
                    assertThat(secrets).doesNotContain(value.toString());
                }
            }
        }
        // Zusatz: die bekannten Hashes sind echte 64-stellige Hex-Werte (also nicht versehentlich "leer").
        assertThat(secrets).allMatch(h -> h != null && h.matches("[0-9a-f]{64}"));
    }
}
