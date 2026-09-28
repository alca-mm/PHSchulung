package de.internal.awareness.service;

import de.internal.awareness.campaign.Campaign;
import de.internal.awareness.campaign.CampaignNotFoundException;
import de.internal.awareness.campaign.CampaignService;
import de.internal.awareness.recipient.CampaignRecipient;
import de.internal.awareness.recipient.CampaignRecipientRepository;
import de.internal.awareness.recipient.RecipientRegistration;
import de.internal.awareness.recipient.RecipientService;
import de.internal.awareness.tracking.TrackingTokens;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-Tests fuer {@link RecipientService} gegen die reale (isolierte) SQLite-Test-DB.
 * Faelle 5-11: Zuordnung zur Kampagne, sichere Tracking-Identitaet, nur Hash persistiert,
 * Klartext-Token entspricht dem Hash, unterschiedliche Tokens, ungueltige E-Mail, unbekannte Kampagne.
 * Zusaetzlich: Redaktion von {@link RecipientRegistration#toString()} (kein Token-Leak).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class RecipientServiceTest {

    @Autowired
    private CampaignService campaignService;

    @Autowired
    private RecipientService recipientService;

    @Autowired
    private CampaignRecipientRepository recipientRepository;

    private Campaign newCampaign() {
        return campaignService.create("Kampagne", null, "Betreff");
    }

    @Test
    void assignsRecipientToCorrectCampaign() {
        Campaign campaign = newCampaign();

        RecipientRegistration reg = recipientService.addRecipient(campaign.getId(), "a@example.invalid", "Alice");

        assertThat(reg.recipient().getId()).isNotNull();
        assertThat(reg.recipient().getCampaign().getId()).isEqualTo(campaign.getId());
        assertThat(reg.recipient().getEmail()).isEqualTo("a@example.invalid");
        assertThat(reg.recipient().getDisplayName()).isEqualTo("Alice");
        assertThat(recipientService.findByCampaign(campaign.getId()))
                .extracting(CampaignRecipient::getEmail)
                .containsExactly("a@example.invalid");
    }

    @Test
    void recipientGetsNewSecureTrackingToken() {
        Campaign campaign = newCampaign();

        RecipientRegistration reg = recipientService.addRecipient(campaign.getId(), "b@example.invalid", null);

        // 256-Bit-Token, Base64URL ohne Padding -> mindestens 43 Zeichen.
        assertThat(reg.plaintextToken()).isNotBlank().hasSizeGreaterThanOrEqualTo(43);
        assertThat(reg.recipient().getDisplayName()).isNull();
    }

    @Test
    void onlyHashIsPersistedNotThePlaintextToken() {
        Campaign campaign = newCampaign();

        RecipientRegistration reg = recipientService.addRecipient(campaign.getId(), "c@example.invalid", null);
        String storedHash = reg.recipient().getTrackingTokenHash();

        // Gespeichert ist der Hash, nicht der Klartext-Token.
        assertThat(storedHash).isNotEqualTo(reg.plaintextToken());
        assertThat(storedHash).hasSize(64).matches("[0-9a-f]{64}");
        // In der DB steht genau dieser Hash; der Klartext-Token taucht nirgends als Spaltenwert auf.
        CampaignRecipient reloaded = recipientRepository.findById(reg.recipient().getId()).orElseThrow();
        assertThat(reloaded.getTrackingTokenHash()).isEqualTo(storedHash);
        assertThat(recipientRepository.findByTrackingTokenHash(reg.plaintextToken())).isEmpty();
    }

    @Test
    void plaintextTokenHashesToStoredHash() {
        Campaign campaign = newCampaign();

        RecipientRegistration reg = recipientService.addRecipient(campaign.getId(), "d@example.invalid", null);

        assertThat(TrackingTokens.hash(reg.plaintextToken())).isEqualTo(reg.recipient().getTrackingTokenHash());
    }

    @Test
    void twoRecipientsGetDifferentTokens() {
        Campaign campaign = newCampaign();

        RecipientRegistration first = recipientService.addRecipient(campaign.getId(), "e@example.invalid", null);
        RecipientRegistration second = recipientService.addRecipient(campaign.getId(), "f@example.invalid", null);

        assertThat(first.plaintextToken()).isNotEqualTo(second.plaintextToken());
        assertThat(first.recipient().getTrackingTokenHash())
                .isNotEqualTo(second.recipient().getTrackingTokenHash());
    }

    // Hinweis: pro Testmethode nur EIN fehlschlagender Flush. Zwei fehlschlagende addRecipient-Aufrufe
    // in derselben (Test-)Transaktion wuerden beim zweiten Flush die bereits id-lose Entity des ersten
    // Fehlversuchs erneut verarbeiten -> org.hibernate.AssertionFailure ("null identifier"). In der
    // Anwendung ist jeder Aufruf eine eigene Transaktion; die Aufteilung bildet das korrekt ab.
    @Test
    void rejectsInvalidEmailFormat() {
        Campaign campaign = newCampaign();

        assertThatThrownBy(() -> recipientService.addRecipient(campaign.getId(), "kein-email", null))
                .isInstanceOfAny(ConstraintViolationException.class, DataIntegrityViolationException.class);
    }

    @Test
    void rejectsBlankEmail() {
        Campaign campaign = newCampaign();

        assertThatThrownBy(() -> recipientService.addRecipient(campaign.getId(), "   ", null))
                .isInstanceOfAny(ConstraintViolationException.class, DataIntegrityViolationException.class);
    }

    @Test
    void unknownCampaignIdOnAddThrowsNotFound() {
        assertThatThrownBy(() -> recipientService.addRecipient(999_999L, "g@example.invalid", null))
                .isInstanceOf(CampaignNotFoundException.class);
    }

    @Test
    void unknownCampaignIdOnFindThrowsNotFound() {
        assertThatThrownBy(() -> recipientService.findByCampaign(999_999L))
                .isInstanceOf(CampaignNotFoundException.class);
    }

    @Test
    void registrationToStringDoesNotLeakPlaintextToken() {
        Campaign campaign = newCampaign();

        RecipientRegistration reg = recipientService.addRecipient(campaign.getId(), "h@example.invalid", null);

        assertThat(reg.toString())
                .doesNotContain(reg.plaintextToken())
                .contains("***redacted***");
    }
}
