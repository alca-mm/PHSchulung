package de.internal.awareness.campaign;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import de.internal.awareness.support.SqliteFlywayJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fall 1: Campaign kann gespeichert und wieder geladen werden.
 */
@SqliteFlywayJpaTest
class CampaignPersistenceTest {

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savesAndReloadsCampaign() {
        Campaign campaign = new Campaign("Q3 Phishing Awareness", "Wichtige Sicherheitsinformation");
        campaign.setDescription("Interner Beschreibungstext");

        Campaign saved = campaignRepository.saveAndFlush(campaign);
        assertThat(saved.getId()).isNotNull();
        entityManager.clear();

        Optional<Campaign> reloaded = campaignRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getName()).isEqualTo("Q3 Phishing Awareness");
        assertThat(reloaded.get().getEmailSubject()).isEqualTo("Wichtige Sicherheitsinformation");
        assertThat(reloaded.get().getDescription()).isEqualTo("Interner Beschreibungstext");
        assertThat(reloaded.get().getStatus()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(reloaded.get().getCreatedAt()).isNotNull();
        assertThat(reloaded.get().getUpdatedAt()).isNotNull();
    }
}
