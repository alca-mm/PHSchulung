package de.internal.awareness.tracking;

import de.internal.awareness.recipient.CampaignRecipient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring-Data-Repository fuer {@link TrackingEvent}.
 */
public interface TrackingEventRepository extends JpaRepository<TrackingEvent, Long> {

    /** Alle Ereignisse eines Empfaengers. */
    List<TrackingEvent> findByRecipient(CampaignRecipient recipient);

    /** Anzahl der Ereignisse eines Empfaengers eines bestimmten Typs (z. B. Klicks). */
    long countByRecipientAndType(CampaignRecipient recipient, TrackingEventType type);
}
