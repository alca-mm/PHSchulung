package de.internal.awareness.recipient;

import de.internal.awareness.campaign.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring-Data-Repository fuer {@link CampaignRecipient}.
 */
public interface CampaignRecipientRepository extends JpaRepository<CampaignRecipient, Long> {

    /** Alle Empfaenger einer Kampagne. */
    List<CampaignRecipient> findByCampaign(Campaign campaign);

    /**
     * Findet einen Empfaenger anhand der gespeicherten sicheren Repraesentation seiner
     * Tracking-Identitaet (SHA-256-Hash des Tokens). Grundlage fuer den spaeteren
     * Tracking-Lookup: eingehender Token -&gt; hashen -&gt; hier nachschlagen.
     */
    Optional<CampaignRecipient> findByTrackingTokenHash(String trackingTokenHash);

    /**
     * Prueft, ob ein Tracking-Hash bereits vergeben ist. Dient der Kollisionsvermeidung beim Anlegen
     * eines Empfaengers (der Unique-Index bleibt der endgueltige Schutz auf DB-Ebene).
     */
    boolean existsByTrackingTokenHash(String trackingTokenHash);

    /** Anzahl aller Empfaenger einer Kampagne (fuer die Uebersicht / "an X Empfaenger versenden"). */
    long countByCampaign(Campaign campaign);

    /** Anzahl der Empfaenger einer Kampagne mit einem bestimmten Versandstatus (fuer die Aufschluesselung). */
    long countByCampaignAndDeliveryStatus(Campaign campaign, DeliveryStatus deliveryStatus);

    /**
     * Prueft (case-insensitive), ob eine Adresse in dieser Kampagne bereits existiert. Grundlage der
     * verstaendlichen Duplikat-Meldung; der Unique-Index (campaign_id, email) bleibt der DB-seitige Schutz.
     */
    boolean existsByCampaignAndEmailIgnoreCase(Campaign campaign, String email);
}
