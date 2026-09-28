package de.internal.awareness.tracking;

import de.internal.awareness.campaign.Campaign;
import de.internal.awareness.campaign.CampaignRepository;
import de.internal.awareness.recipient.CampaignRecipient;
import de.internal.awareness.recipient.CampaignRecipientRepository;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import de.internal.awareness.support.SqliteFlywayJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faelle 5, 6, 7 und 9 (event-bezogen): mehrere LINK_CLICK-Events, korrekte Klickanzahl,
 * keine Vermischung zwischen Empfaengern, Event benoetigt immer einen CampaignRecipient.
 */
@SqliteFlywayJpaTest
class TrackingEventPersistenceTest {

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignRecipientRepository recipientRepository;

    @Autowired
    private TrackingEventRepository eventRepository;

    @Autowired
    private TestEntityManager entityManager;

    private CampaignRecipient newRecipient(String email) {
        Campaign campaign = campaignRepository.saveAndFlush(new Campaign("Kampagne", "Betreff"));
        return recipientRepository.saveAndFlush(
                new CampaignRecipient(campaign, email, TrackingTokens.generate().tokenHash()));
    }

    @Test
    void storesMultipleLinkClicksForSameRecipient() {
        CampaignRecipient recipient = newRecipient("a@example.com");
        eventRepository.saveAndFlush(new TrackingEvent(recipient, TrackingEventType.LINK_CLICK));
        eventRepository.saveAndFlush(new TrackingEvent(recipient, TrackingEventType.LINK_CLICK));
        eventRepository.saveAndFlush(new TrackingEvent(recipient, TrackingEventType.LINK_CLICK));
        entityManager.clear();

        List<TrackingEvent> events = eventRepository.findByRecipient(recipient);
        assertThat(events).hasSize(3);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.getType()).isEqualTo(TrackingEventType.LINK_CLICK);
            assertThat(e.getOccurredAt()).isNotNull();
        });
    }

    @Test
    void countsClickEventsOfRecipient() {
        CampaignRecipient recipient = newRecipient("a@example.com");
        eventRepository.saveAndFlush(new TrackingEvent(recipient, TrackingEventType.LINK_CLICK));
        eventRepository.saveAndFlush(new TrackingEvent(recipient, TrackingEventType.LINK_CLICK));

        assertThat(eventRepository.countByRecipientAndType(recipient, TrackingEventType.LINK_CLICK))
                .isEqualTo(2L);
    }

    @Test
    void eventsOfDifferentRecipientsAreNotMixed() {
        CampaignRecipient first = newRecipient("a@example.com");
        CampaignRecipient second = newRecipient("b@example.com");
        eventRepository.saveAndFlush(new TrackingEvent(first, TrackingEventType.LINK_CLICK));
        eventRepository.saveAndFlush(new TrackingEvent(first, TrackingEventType.LINK_CLICK));
        eventRepository.saveAndFlush(new TrackingEvent(second, TrackingEventType.LINK_CLICK));
        entityManager.clear();

        assertThat(eventRepository.countByRecipientAndType(first, TrackingEventType.LINK_CLICK)).isEqualTo(2L);
        assertThat(eventRepository.countByRecipientAndType(second, TrackingEventType.LINK_CLICK)).isEqualTo(1L);
        assertThat(eventRepository.findByRecipient(first)).hasSize(2);
        assertThat(eventRepository.findByRecipient(second)).hasSize(1);
    }

    @Test
    void eventRequiresRecipient() {
        TrackingEvent event = new TrackingEvent(null, TrackingEventType.LINK_CLICK);
        assertThatThrownBy(() -> eventRepository.saveAndFlush(event))
                .isInstanceOfAny(ConstraintViolationException.class, DataIntegrityViolationException.class);
    }
}
