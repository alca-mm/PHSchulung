package de.internal.awareness.service;

import de.internal.awareness.campaign.Campaign;
import de.internal.awareness.campaign.CampaignNotFoundException;
import de.internal.awareness.campaign.CampaignService;
import de.internal.awareness.campaign.CampaignStatus;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-Tests fuer {@link CampaignService} gegen die reale (isolierte) SQLite-Test-DB.
 * Faelle 1-4: Anlage mit Status DRAFT, Ablehnung ungueltiger Daten, Laden per ID, kontrollierte
 * Behandlung unbekannter IDs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class CampaignServiceTest {

    @Autowired
    private CampaignService campaignService;

    @Test
    void createsCampaignWithDraftStatus() {
        Campaign created = campaignService.create("Q3 Awareness", "Interne Info", "Wichtiger Betreff");

        assertThat(created.getId()).isNotNull();
        assertThat(created.getName()).isEqualTo("Q3 Awareness");
        assertThat(created.getDescription()).isEqualTo("Interne Info");
        assertThat(created.getEmailSubject()).isEqualTo("Wichtiger Betreff");
        assertThat(created.getStatus()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();
    }

    @Test
    void createsCampaignWithoutDescription() {
        Campaign created = campaignService.create("Ohne Beschreibung", "   ", "Betreff");
        assertThat(created.getDescription()).isNull();
    }

    @Test
    void rejectsInvalidCampaignData() {
        // Leerer Name / Betreff verletzt Bean Validation (@NotBlank) beim Flush.
        assertThatThrownBy(() -> campaignService.create("   ", null, "Betreff"))
                .isInstanceOfAny(ConstraintViolationException.class, DataIntegrityViolationException.class);
        assertThatThrownBy(() -> campaignService.create("Name", null, "  "))
                .isInstanceOfAny(ConstraintViolationException.class, DataIntegrityViolationException.class);
    }

    @Test
    void loadsCampaignById() {
        Campaign created = campaignService.create("Ladbar", null, "Betreff");

        Campaign loaded = campaignService.getById(created.getId());
        assertThat(loaded.getId()).isEqualTo(created.getId());
        assertThat(loaded.getName()).isEqualTo("Ladbar");
    }

    @Test
    void unknownCampaignIdThrowsNotFound() {
        assertThatThrownBy(() -> campaignService.getById(999_999L))
                .isInstanceOf(CampaignNotFoundException.class)
                .hasMessageContaining("999999");
    }

    @Test
    void findAllReturnsNewestFirst() {
        Campaign first = campaignService.create("Erste", null, "B");
        Campaign second = campaignService.create("Zweite", null, "B");

        List<Campaign> all = campaignService.findAll();
        // Neueste zuerst: die zuletzt angelegte Kampagne steht vor der zuerst angelegten.
        assertThat(all).extracting(Campaign::getId).containsSubsequence(second.getId(), first.getId());
    }
}
