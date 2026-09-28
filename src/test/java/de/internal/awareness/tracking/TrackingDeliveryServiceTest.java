package de.internal.awareness.tracking;

import de.internal.awareness.contact.Contact;
import de.internal.awareness.contact.ContactRepository;
import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailBatchRepository;
import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.mail.MailDeliveryRepository;
import de.internal.awareness.recipient.DeliveryStatus;
import de.internal.awareness.tracking.TrackingDeliveryService.DeliveryDetail;
import de.internal.awareness.tracking.TrackingDeliveryService.TimelineEntry;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TrackingDeliveryService} gegen die reale (isolierte) SQLite-Test-DB ({@code @SpringBootTest(NONE)} +
 * {@code @Transactional} mit Rollback je Testmethode). Deckt die Aufbereitung der Detailsicht ab: Feldwerte,
 * chronologische Timeline, min/max-Zeitpunkte (erster/letzter), actionCount/triggered, Leerzustand (getrackt
 * ohne Ereignis), leeres {@link Optional} bei unbekannter/null-Id sowie - sicherheitskritisch - dass KEIN
 * Token-Hash in den {@link DeliveryDetail}-Feldern oder deren {@code toString} auftaucht.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class TrackingDeliveryServiceTest {

    @TempDir
    static Path generatedDir;

    @DynamicPropertySource
    static void fileProps(DynamicPropertyRegistry registry) {
        registry.add("app.files.generated-dir", () -> generatedDir.toString());
    }

    @Autowired
    private TrackingDeliveryService service;

    @Autowired
    private MailBatchRepository batchRepository;

    @Autowired
    private MailDeliveryRepository deliveryRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private MailTrackingEventRepository eventRepository;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private MailDelivery seed(String name, String email, String subject, String filename, String hash,
                             DeliveryStatus status, Instant sentAt, int attempts, Instant... clicks) {
        Contact contact = contactRepository.saveAndFlush(new Contact(email, name));
        MailBatch batch = batchRepository.saveAndFlush(new MailBatch(
                subject, "Hallo", "training@example.invalid", null, null, filename, 1));
        MailDelivery delivery = new MailDelivery(batch, contact, hash);
        for (int i = 0; i < attempts; i++) {
            delivery.recordAttempt();
        }
        if (status == DeliveryStatus.SENT) {
            delivery.recordSent(sentAt);
        } else if (status == DeliveryStatus.FAILED) {
            delivery.recordFailure("SEND");
        }
        delivery = deliveryRepository.saveAndFlush(delivery);
        for (Instant t : clicks) {
            eventRepository.save(new MailTrackingEvent(delivery, TrackingEventType.LINK_CLICK, t));
        }
        eventRepository.flush();
        return delivery;
    }

    @Test
    void findDeliveryReturnsAllDetailFields() {
        MailDelivery d = seed("Alice Anderson", "alice@example.invalid", "Rechnung September", "rechnung.docx",
                TrackingTokens.generate().tokenHash(), DeliveryStatus.SENT,
                now.minus(2, ChronoUnit.DAYS), 2);

        DeliveryDetail detail = service.findDelivery(d.getId()).orElseThrow();
        assertThat(detail.deliveryId()).isEqualTo(d.getId());
        assertThat(detail.recipientName()).isEqualTo("Alice Anderson");
        assertThat(detail.email()).isEqualTo("alice@example.invalid");
        assertThat(detail.batchId()).isEqualTo(d.getBatch().getId());
        assertThat(detail.batchSubject()).isEqualTo("Rechnung September");
        assertThat(detail.attachmentFilename()).isEqualTo("rechnung.docx");
        assertThat(detail.sentAt()).isEqualTo(now.minus(2, ChronoUnit.DAYS));
        assertThat(detail.status()).isEqualTo(DeliveryStatus.SENT);
        assertThat(detail.attemptCount()).isEqualTo(2);
    }

    @Test
    void timelineIsChronologicalAndFirstLastAreMinMax() {
        Instant t1 = now.minus(40, ChronoUnit.MINUTES);
        Instant t2 = now.minus(20, ChronoUnit.MINUTES);
        Instant t3 = now.minus(5, ChronoUnit.MINUTES);
        // Einfuegereihenfolge bewusst unsortiert (t3, t1, t2).
        MailDelivery d = seed("Bob Baker", "bob@example.invalid", "Hinweis", "hinweis.pdf",
                TrackingTokens.generate().tokenHash(), DeliveryStatus.SENT, now.minus(1, ChronoUnit.HOURS), 1,
                t3, t1, t2);

        DeliveryDetail detail = service.findDelivery(d.getId()).orElseThrow();
        assertThat(detail.triggered()).isTrue();
        assertThat(detail.actionCount()).isEqualTo(3L);
        assertThat(detail.timeline()).extracting(TimelineEntry::occurredAt).containsExactly(t1, t2, t3);
        assertThat(detail.timeline()).extracting(TimelineEntry::type)
                .containsExactly(TrackingEventType.LINK_CLICK, TrackingEventType.LINK_CLICK,
                        TrackingEventType.LINK_CLICK);
        assertThat(detail.firstAction()).isEqualTo(t1);
        assertThat(detail.lastAction()).isEqualTo(t3);
    }

    @Test
    void trackedDeliveryWithoutEventsHasEmptyTimeline() {
        MailDelivery d = seed("Carol Clark", "carol@example.invalid", "Newsletter", "info.docx",
                TrackingTokens.generate().tokenHash(), DeliveryStatus.SENT, now, 1);

        DeliveryDetail detail = service.findDelivery(d.getId()).orElseThrow();
        assertThat(detail.triggered()).isFalse();
        assertThat(detail.actionCount()).isZero();
        assertThat(detail.timeline()).isEmpty();
        assertThat(detail.firstAction()).isNull();
        assertThat(detail.lastAction()).isNull();
    }

    @Test
    void notSentDeliveryWithoutAttachmentIsHandled() {
        MailDelivery d = seed("Dave Dunn", "dave@example.invalid", "Entwurf", null,
                TrackingTokens.generate().tokenHash(), DeliveryStatus.NOT_SENT, null, 0);

        DeliveryDetail detail = service.findDelivery(d.getId()).orElseThrow();
        assertThat(detail.attachmentFilename()).isNull();
        assertThat(detail.sentAt()).isNull();
        assertThat(detail.status()).isEqualTo(DeliveryStatus.NOT_SENT);
        assertThat(detail.attemptCount()).isZero();
    }

    @Test
    void unknownIdReturnsEmptyOptional() {
        assertThat(service.findDelivery(999_999L)).isEmpty();
    }

    @Test
    void nullIdReturnsEmptyOptional() {
        assertThat(service.findDelivery(null)).isEmpty();
    }

    @Test
    void noTokenHashLeaksIntoDeliveryDetail() {
        String hash = TrackingTokens.generate().tokenHash();
        MailDelivery d = seed("Erin Evans", "erin@example.invalid", "Reset", "reset.docx",
                hash, DeliveryStatus.SENT, now, 1, now.minus(1, ChronoUnit.MINUTES));

        Optional<DeliveryDetail> result = service.findDelivery(d.getId());
        assertThat(result).isPresent();
        DeliveryDetail detail = result.get();

        // 1) toString darf den Hash nicht enthalten.
        assertThat(detail.toString()).doesNotContain(hash);
        // 2) Kein einzelnes Record-Feld ist gleich dem bekannten Hash.
        for (RecordComponent component : DeliveryDetail.class.getRecordComponents()) {
            Object value;
            try {
                value = component.getAccessor().invoke(detail);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Zugriff auf DeliveryDetail-Komponente fehlgeschlagen: "
                        + component.getName(), e);
            }
            if (value != null) {
                assertThat(value.toString()).as("Feld %s darf keinen Hash enthalten", component.getName())
                        .doesNotContain(hash);
            }
        }
        // Der bekannte Hash ist ein echter 64-stelliger Hex-Wert (also nicht versehentlich "leer").
        assertThat(hash).matches("[0-9a-f]{64}");
    }
}
