package de.internal.awareness.service;

import de.internal.awareness.campaign.Campaign;
import de.internal.awareness.campaign.CampaignRepository;
import de.internal.awareness.recipient.BulkRecipientParser;
import de.internal.awareness.recipient.CampaignRecipient;
import de.internal.awareness.recipient.CampaignRecipientRepository;
import de.internal.awareness.recipient.RecipientRegistration;
import de.internal.awareness.recipient.RecipientService;
import de.internal.awareness.recipient.TrackingTokenCollisionException;
import de.internal.awareness.tracking.TrackingTokenFactory;
import de.internal.awareness.tracking.TrackingTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fall 12: Eine (praktisch unmoegliche) Tracking-Token-Kollision fuehrt nicht zu einem fehlerhaften
 * Datensatz. Ueber eine eingespeiste {@link TrackingTokenFactory} wird die Kollision deterministisch
 * erzwungen: Der erste Kandidat trifft einen bereits vergebenen Hash, der zweite ist frei.
 *
 * <p>Der Service wird bewusst manuell mit der Stub-Factory verdrahtet (echte Repositories aus dem
 * Kontext); die {@code @Transactional}-Testmethode umschliesst die Persistenz und rollt zurueck.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class RecipientServiceTokenCollisionTest {

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignRecipientRepository recipientRepository;

    @Autowired
    private BulkRecipientParser bulkParser;

    /** Liefert die uebergebenen Tokens der Reihe nach (fuer deterministische Kollision). */
    private static TrackingTokenFactory sequenceFactory(TrackingTokens.GeneratedToken... tokens) {
        Deque<TrackingTokens.GeneratedToken> queue = new ArrayDeque<>(List.of(tokens));
        return queue::removeFirst;
    }

    @Test
    void collisionIsRetriedAndDoesNotCreateFaultyOrDuplicateRecord() {
        Campaign campaign = campaignRepository.saveAndFlush(new Campaign("Kampagne", "Betreff"));

        TrackingTokens.GeneratedToken colliding = TrackingTokens.generate();
        TrackingTokens.GeneratedToken free = TrackingTokens.generate();

        // Ein bestehender Empfaenger belegt den Hash des ersten Kandidaten.
        recipientRepository.saveAndFlush(
                new CampaignRecipient(campaign, "existing@example.invalid", colliding.tokenHash()));

        RecipientService service = new RecipientService(
                campaignRepository, recipientRepository, sequenceFactory(colliding, free), bulkParser);

        RecipientRegistration reg = service.addRecipient(campaign.getId(), "new@example.invalid", null);

        // Der bereits vergebene (kollidierende) Hash wurde NICHT erneut gespeichert.
        assertThat(reg.recipient().getTrackingTokenHash()).isEqualTo(free.tokenHash());
        assertThat(reg.plaintextToken()).isEqualTo(free.token());
        // Genau zwei Empfaenger, jeder mit eindeutigem Hash; kein fehlerhafter/doppelter Datensatz.
        List<CampaignRecipient> all = recipientRepository.findByCampaign(campaign);
        assertThat(all).hasSize(2)
                .extracting(CampaignRecipient::getTrackingTokenHash)
                .containsExactlyInAnyOrder(colliding.tokenHash(), free.tokenHash());
    }

    @Test
    void exhaustedRetriesThrowInsteadOfLoopingForever() {
        Campaign campaign = campaignRepository.saveAndFlush(new Campaign("Kampagne", "Betreff"));

        TrackingTokens.GeneratedToken taken = TrackingTokens.generate();
        recipientRepository.saveAndFlush(
                new CampaignRecipient(campaign, "existing@example.invalid", taken.tokenHash()));

        // Factory liefert IMMER denselben, bereits vergebenen Hash -> begrenzte Versuche, dann Fehler.
        TrackingTokenFactory alwaysColliding = () -> taken;
        RecipientService service = new RecipientService(campaignRepository, recipientRepository, alwaysColliding, bulkParser);

        assertThatThrownBy(() -> service.addRecipient(campaign.getId(), "new@example.invalid", null))
                .isInstanceOf(TrackingTokenCollisionException.class);
        // Kein zusaetzlicher (fehlerhafter) Datensatz entstanden.
        assertThat(recipientRepository.findByCampaign(campaign)).hasSize(1);
    }
}
