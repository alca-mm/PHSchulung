package de.internal.awareness.recipient;

import de.internal.awareness.campaign.Campaign;
import de.internal.awareness.campaign.CampaignRepository;
import de.internal.awareness.tracking.TrackingTokens;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import de.internal.awareness.support.SqliteFlywayJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faelle 2, 3, 4, 8 und 9 (recipient-bezogen): Zuordnung zur Kampagne, unterschiedliche und
 * eindeutige Tracking-Identitaeten, Abfrage der Empfaenger einer Kampagne, zentrale Constraints.
 */
@SqliteFlywayJpaTest
class CampaignRecipientPersistenceTest {

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignRecipientRepository recipientRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Campaign newCampaign(String name) {
        return campaignRepository.saveAndFlush(new Campaign(name, "Betreff"));
    }

    @Test
    void assignsRecipientToCampaign() {
        Campaign campaign = newCampaign("Kampagne A");
        CampaignRecipient recipient =
                new CampaignRecipient(campaign, "alice@example.com", TrackingTokens.generate().tokenHash());
        recipient.setDisplayName("Alice");

        CampaignRecipient saved = recipientRepository.saveAndFlush(recipient);
        entityManager.clear();

        Optional<CampaignRecipient> reloaded = recipientRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getCampaign().getId()).isEqualTo(campaign.getId());
        assertThat(reloaded.get().getEmail()).isEqualTo("alice@example.com");
        assertThat(reloaded.get().getDisplayName()).isEqualTo("Alice");
        assertThat(reloaded.get().getCreatedAt()).isNotNull();
    }

    @Test
    void twoRecipientsHaveDifferentTrackingIdentities() {
        Campaign campaign = newCampaign("Kampagne B");
        String hash1 = TrackingTokens.generate().tokenHash();
        String hash2 = TrackingTokens.generate().tokenHash();
        assertThat(hash1).isNotEqualTo(hash2);

        recipientRepository.saveAndFlush(new CampaignRecipient(campaign, "a@example.com", hash1));
        recipientRepository.saveAndFlush(new CampaignRecipient(campaign, "b@example.com", hash2));
        entityManager.clear();

        assertThat(recipientRepository.findByTrackingTokenHash(hash1))
                .isPresent()
                .get()
                .extracting(CampaignRecipient::getEmail)
                .isEqualTo("a@example.com");
        assertThat(recipientRepository.findByTrackingTokenHash(hash2))
                .isPresent()
                .get()
                .extracting(CampaignRecipient::getEmail)
                .isEqualTo("b@example.com");
    }

    @Test
    void trackingIdentityLookupResolvesKnownAndUnknownTokens() {
        Campaign campaign = newCampaign("Kampagne C");
        String knownHash = TrackingTokens.generate().tokenHash();
        recipientRepository.saveAndFlush(new CampaignRecipient(campaign, "a@example.com", knownHash));
        entityManager.clear();

        assertThat(recipientRepository.findByTrackingTokenHash(knownHash)).isPresent();
        assertThat(recipientRepository.findByTrackingTokenHash(TrackingTokens.generate().tokenHash())).isEmpty();
    }

    @Test
    void duplicateTrackingIdentityIsRejected() {
        Campaign campaign = newCampaign("Kampagne D");
        String hash = TrackingTokens.generate().tokenHash();
        recipientRepository.saveAndFlush(new CampaignRecipient(campaign, "a@example.com", hash));

        CampaignRecipient duplicate = new CampaignRecipient(campaign, "b@example.com", hash);
        // Der Unique-Index auf tracking_token_hash lehnt das Duplikat auf DB-Ebene ab.
        // SQLite/Hibernate melden dies als DataAccessException mit "UNIQUE constraint failed".
        assertThatThrownBy(() -> recipientRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("UNIQUE constraint failed");
    }

    @Test
    void recipientsOfACampaignCanBeQueriedReliably() {
        Campaign campaign = newCampaign("Kampagne E");
        Campaign other = newCampaign("Kampagne F");
        recipientRepository.saveAndFlush(
                new CampaignRecipient(campaign, "a@example.com", TrackingTokens.generate().tokenHash()));
        recipientRepository.saveAndFlush(
                new CampaignRecipient(campaign, "b@example.com", TrackingTokens.generate().tokenHash()));
        recipientRepository.saveAndFlush(
                new CampaignRecipient(other, "c@example.com", TrackingTokens.generate().tokenHash()));
        entityManager.clear();

        List<CampaignRecipient> recipients = recipientRepository.findByCampaign(campaign);
        assertThat(recipients)
                .hasSize(2)
                .extracting(CampaignRecipient::getEmail)
                .containsExactlyInAnyOrder("a@example.com", "b@example.com");
    }

    @Test
    void blankEmailIsRejected() {
        Campaign campaign = newCampaign("Kampagne G");
        CampaignRecipient recipient =
                new CampaignRecipient(campaign, "   ", TrackingTokens.generate().tokenHash());

        assertThatThrownBy(() -> recipientRepository.saveAndFlush(recipient))
                .isInstanceOfAny(ConstraintViolationException.class, DataIntegrityViolationException.class);
    }
}
