package de.internal.awareness.tracking;

import de.internal.awareness.contact.Contact;
import de.internal.awareness.contact.ContactRepository;
import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailBatchRepository;
import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.mail.MailDeliveryRepository;
import de.internal.awareness.support.SqliteFlywayJpaTest;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistenz der empfaengerbezogenen Tracking-Identitaet ({@link MailDelivery#getTrackingTokenHash()}) und
 * der Klick-Ereignisse ({@link MailTrackingEvent}): nur Hash gespeichert, eindeutig, Alt-Daten (NULL)
 * vertraeglich, Mehrfachklicks, erster/letzter Klick, keine Vermischung zwischen Zustellungen, Batch-Aggregate.
 */
@SqliteFlywayJpaTest
class MailTrackingPersistenceTest {

    @Autowired
    private MailBatchRepository batchRepository;

    @Autowired
    private MailDeliveryRepository deliveryRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private MailTrackingEventRepository eventRepository;

    @Autowired
    private TestEntityManager entityManager;

    private MailBatch newBatch() {
        return batchRepository.saveAndFlush(new MailBatch(
                "Betreff", "Hallo", "training@example.invalid", null, null, null, 1));
    }

    private Contact newContact(String email) {
        return contactRepository.saveAndFlush(new Contact(email, "Name " + email));
    }

    private MailDelivery newDelivery(MailBatch batch, String email, String hash) {
        return deliveryRepository.saveAndFlush(new MailDelivery(batch, newContact(email), hash));
    }

    @Test
    void deliveryStoresOnlyHashAndIsFoundByHash() {
        MailBatch batch = newBatch();
        TrackingTokens.GeneratedToken token = TrackingTokens.generate();
        MailDelivery saved = newDelivery(batch, "a@example.invalid", token.tokenHash());
        entityManager.clear();

        assertThat(deliveryRepository.findByTrackingTokenHash(token.tokenHash()))
                .isPresent().get().extracting(MailDelivery::getId).isEqualTo(saved.getId());
        // Der Klartext-Token ist NICHT gespeichert: eine Suche nach dem Klartext findet nichts.
        assertThat(deliveryRepository.findByTrackingTokenHash(token.token())).isEmpty();
        assertThat(saved.getTrackingTokenHash()).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(token.token());
    }

    @Test
    void twoDeliveriesHaveDifferentHashes() {
        MailBatch batch = newBatch();
        String h1 = TrackingTokens.generate().tokenHash();
        String h2 = TrackingTokens.generate().tokenHash();
        assertThat(h1).isNotEqualTo(h2);
        newDelivery(batch, "a@example.invalid", h1);
        newDelivery(batch, "b@example.invalid", h2);

        assertThat(deliveryRepository.findByTrackingTokenHash(h1)).isPresent();
        assertThat(deliveryRepository.findByTrackingTokenHash(h2)).isPresent();
    }

    @Test
    void multipleNullHashesAreAllowed() {
        // Migrationssicherheit: Alt-Deliveries ohne Token (NULL) muessen koexistieren koennen
        // (SQLite behandelt mehrere NULLs im Unique-Index als verschieden).
        MailBatch batch = newBatch();
        newDelivery(batch, "a@example.invalid", null);
        newDelivery(batch, "b@example.invalid", null);

        assertThat(deliveryRepository.findByBatch(batch)).hasSize(2);
    }

    @Test
    void duplicateHashIsRejected() {
        MailBatch batch = newBatch();
        String hash = TrackingTokens.generate().tokenHash();
        newDelivery(batch, "a@example.invalid", hash);

        assertThatThrownBy(() -> newDelivery(batch, "b@example.invalid", hash))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("UNIQUE constraint failed");
    }

    @Test
    void storesMultipleClickEventsForSameDeliveryAndCounts() {
        MailBatch batch = newBatch();
        MailDelivery delivery = newDelivery(batch, "a@example.invalid", TrackingTokens.generate().tokenHash());
        eventRepository.saveAndFlush(new MailTrackingEvent(delivery, TrackingEventType.LINK_CLICK));
        eventRepository.saveAndFlush(new MailTrackingEvent(delivery, TrackingEventType.LINK_CLICK));
        eventRepository.saveAndFlush(new MailTrackingEvent(delivery, TrackingEventType.LINK_CLICK));
        entityManager.clear();

        MailDelivery reloaded = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(eventRepository.countByDelivery(reloaded)).isEqualTo(3L);
    }

    @Test
    void firstAndLastClickAreCorrect() {
        MailBatch batch = newBatch();
        MailDelivery delivery = newDelivery(batch, "a@example.invalid", TrackingTokens.generate().tokenHash());
        Instant t1 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant t2 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant t3 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        eventRepository.saveAndFlush(new MailTrackingEvent(delivery, TrackingEventType.LINK_CLICK, t2));
        eventRepository.saveAndFlush(new MailTrackingEvent(delivery, TrackingEventType.LINK_CLICK, t1));
        eventRepository.saveAndFlush(new MailTrackingEvent(delivery, TrackingEventType.LINK_CLICK, t3));
        entityManager.clear();

        MailDelivery reloaded = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(eventRepository.findFirstByDeliveryOrderByOccurredAtAsc(reloaded))
                .get().extracting(MailTrackingEvent::getOccurredAt).isEqualTo(t1);
        assertThat(eventRepository.findFirstByDeliveryOrderByOccurredAtDesc(reloaded))
                .get().extracting(MailTrackingEvent::getOccurredAt).isEqualTo(t3);
    }

    @Test
    void eventsOfDifferentDeliveriesAreNotMixed() {
        MailBatch batch = newBatch();
        MailDelivery first = newDelivery(batch, "a@example.invalid", TrackingTokens.generate().tokenHash());
        MailDelivery second = newDelivery(batch, "b@example.invalid", TrackingTokens.generate().tokenHash());
        eventRepository.saveAndFlush(new MailTrackingEvent(first, TrackingEventType.LINK_CLICK));
        eventRepository.saveAndFlush(new MailTrackingEvent(first, TrackingEventType.LINK_CLICK));
        eventRepository.saveAndFlush(new MailTrackingEvent(second, TrackingEventType.LINK_CLICK));
        entityManager.clear();

        assertThat(eventRepository.countByDelivery(deliveryRepository.findById(first.getId()).orElseThrow()))
                .isEqualTo(2L);
        assertThat(eventRepository.countByDelivery(deliveryRepository.findById(second.getId()).orElseThrow()))
                .isEqualTo(1L);
    }

    @Test
    void batchAggregatesCountEventsAndRespondingDeliveries() {
        MailBatch batch = newBatch();
        MailDelivery a = newDelivery(batch, "a@example.invalid", TrackingTokens.generate().tokenHash());
        MailDelivery b = newDelivery(batch, "b@example.invalid", TrackingTokens.generate().tokenHash());
        newDelivery(batch, "c@example.invalid", TrackingTokens.generate().tokenHash()); // ohne Klick
        eventRepository.saveAndFlush(new MailTrackingEvent(a, TrackingEventType.LINK_CLICK));
        eventRepository.saveAndFlush(new MailTrackingEvent(a, TrackingEventType.LINK_CLICK));
        eventRepository.saveAndFlush(new MailTrackingEvent(b, TrackingEventType.LINK_CLICK));
        entityManager.clear();

        MailBatch reloaded = batchRepository.findById(batch.getId()).orElseThrow();
        assertThat(eventRepository.countEventsForBatch(reloaded)).isEqualTo(3L);
        assertThat(eventRepository.countRespondingDeliveriesForBatch(reloaded)).isEqualTo(2L);
    }

    @Test
    void eventRequiresDelivery() {
        assertThatThrownBy(() -> eventRepository.saveAndFlush(
                new MailTrackingEvent(null, TrackingEventType.LINK_CLICK)))
                .isInstanceOfAny(ConstraintViolationException.class, DataIntegrityViolationException.class);
    }
}
