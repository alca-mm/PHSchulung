package de.internal.awareness.tracking;

import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring-Data-Repository fuer {@link MailTrackingEvent} (Composer-Tracking je {@link MailDelivery}).
 */
public interface MailTrackingEventRepository extends JpaRepository<MailTrackingEvent, Long> {

    /** Anzahl aller Ereignisse (Klicks) einer Zustellung. */
    long countByDelivery(MailDelivery delivery);

    /** Fruehestes Ereignis einer Zustellung (fuer "erster Klick"). */
    Optional<MailTrackingEvent> findFirstByDeliveryOrderByOccurredAtAsc(MailDelivery delivery);

    /** Spaetestes Ereignis einer Zustellung (fuer "letzter Klick"). */
    Optional<MailTrackingEvent> findFirstByDeliveryOrderByOccurredAtDesc(MailDelivery delivery);

    /** Gesamtzahl der Klickereignisse ueber alle Zustellungen eines Versandvorgangs. */
    @Query("select count(e) from MailTrackingEvent e where e.delivery.batch = :batch")
    long countEventsForBatch(@Param("batch") MailBatch batch);

    /** Anzahl der Empfaenger (Zustellungen) eines Versandvorgangs mit mindestens einem Klick. */
    @Query("select count(distinct e.delivery.id) from MailTrackingEvent e where e.delivery.batch = :batch")
    long countRespondingDeliveriesForBatch(@Param("batch") MailBatch batch);
}
