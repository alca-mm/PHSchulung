package de.internal.awareness.mail;

import de.internal.awareness.recipient.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring-Data-Repository fuer {@link MailDelivery}.
 */
public interface MailDeliveryRepository extends JpaRepository<MailDelivery, Long> {

    /** Alle Zustellungen eines Versandvorgangs. */
    List<MailDelivery> findByBatch(MailBatch batch);

    /** Anzahl der Zustellungen eines Versandvorgangs mit einem bestimmten Status (fuer die Historie). */
    long countByBatchAndStatus(MailBatch batch, DeliveryStatus status);

    /**
     * Findet eine Zustellung anhand der gespeicherten sicheren Repraesentation ihrer Tracking-Identitaet
     * (SHA-256-Hash des Tokens). Grundlage fuer den Tracking-Lookup: eingehender Token -&gt; hashen -&gt;
     * hier nachschlagen.
     */
    Optional<MailDelivery> findByTrackingTokenHash(String trackingTokenHash);

    /**
     * Prueft, ob ein Tracking-Hash bereits vergeben ist (Kollisionsvermeidung beim Anlegen; der Unique-Index
     * bleibt der endgueltige Schutz auf DB-Ebene).
     */
    boolean existsByTrackingTokenHash(String trackingTokenHash);
}
