package de.internal.awareness.recipient;

import de.internal.awareness.campaign.Campaign;
import de.internal.awareness.campaign.CampaignService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Service-Tests fuer den Massenimport und die Statistik von {@link RecipientService} gegen die reale
 * (isolierte) SQLite-Test-DB. Deckt die Faelle #7-#11 ab: Massenimport, Ignorieren leerer Zeilen,
 * Meldung ungueltiger Adressen, Duplikat-Behandlung (innerhalb eines Imports und ueber mehrere Aufrufe
 * sowie beim Einzel-{@code addRecipient}) und die Erlaubnis derselben Adresse in verschiedenen Kampagnen.
 * Zusaetzlich: {@link RecipientService#stats(Long)}.
 *
 * <p>Hinweis (wie im bestehenden {@code RecipientServiceTest}): pro Testmethode hoechstens EIN
 * fehlschlagender Vorgang. Die Duplikat-Vorpruefung wirft VOR jedem Flush, der Massenimport speichert
 * niemals ungueltige oder doppelte Zeilen - daher entsteht kein "null identifier"-Zustand von
 * Hibernate.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class RecipientServiceBulkTest {

    @Autowired
    private CampaignService campaignService;

    @Autowired
    private RecipientService recipientService;

    @Autowired
    private CampaignRecipientRepository recipientRepository;

    private Campaign newCampaign() {
        return campaignService.create("Kampagne", null, "Betreff");
    }

    // Fall #7: Massenimport legt mehrere Empfaenger an; alle sind persistiert und abfragbar.
    @Test
    void bulkAddsSeveralRecipients() {
        Campaign campaign = newCampaign();

        BulkImportResult result = recipientService.importRecipients(campaign.getId(),
                "a@example.invalid\nb@example.invalid\nMax Mustermann <c@example.invalid>");

        assertThat(result.added()).isEqualTo(3);
        assertThat(result.duplicates()).isZero();
        assertThat(result.invalidLines()).isEmpty();
        assertThat(recipientService.findByCampaign(campaign.getId()))
                .extracting(CampaignRecipient::getEmail)
                .containsExactlyInAnyOrder("a@example.invalid", "b@example.invalid", "c@example.invalid");
    }

    // Fall #8: leere Zeilen im Import werden ignoriert (nicht mitgezaehlt, nicht angelegt).
    @Test
    void blankLinesAreIgnored() {
        Campaign campaign = newCampaign();

        BulkImportResult result = recipientService.importRecipients(campaign.getId(),
                "\n\na@example.invalid\n   \n\nb@example.invalid\n\n");

        assertThat(result.added()).isEqualTo(2);
        assertThat(result.duplicates()).isZero();
        assertThat(result.invalidLines()).isEmpty();
        assertThat(recipientRepository.countByCampaign(campaign)).isEqualTo(2);
    }

    // Fall #9: ungueltige Adresse wird gemeldet und nicht angelegt.
    @Test
    void invalidEmailIsReportedNotAdded() {
        Campaign campaign = newCampaign();

        BulkImportResult result = recipientService.importRecipients(campaign.getId(),
                "ok@example.invalid\nkein-email");

        assertThat(result.added()).isEqualTo(1);
        assertThat(result.invalidLines()).containsExactly("kein-email");
        assertThat(recipientService.findByCampaign(campaign.getId()))
                .extracting(CampaignRecipient::getEmail)
                .containsExactly("ok@example.invalid");
    }

    // Fall #10: Duplikate innerhalb desselben Imports UND ueber zwei Aufrufe hinweg fuehren nur zu EINER
    // gespeicherten Zeile; jede weitere Nennung erhoeht den Duplikat-Zaehler.
    @Test
    void duplicatesAreNotDoubleInserted() {
        Campaign campaign = newCampaign();

        BulkImportResult first = recipientService.importRecipients(campaign.getId(),
                "dup@example.invalid\nDUP@example.invalid");
        assertThat(first.added()).isEqualTo(1);
        assertThat(first.duplicates()).isEqualTo(1);

        BulkImportResult second = recipientService.importRecipients(campaign.getId(),
                "dup@example.invalid");
        assertThat(second.added()).isZero();
        assertThat(second.duplicates()).isEqualTo(1);

        assertThat(recipientRepository.countByCampaign(campaign)).isEqualTo(1);
    }

    // Fall #10: ein einzelner addRecipient einer bereits vorhandenen Adresse wirft DuplicateRecipientException
    // (Vorpruefung vor dem Flush - kein zweiter fehlschlagender Flush in dieser Transaktion).
    @Test
    void addRecipientRejectsDuplicateInSameCampaign() {
        Campaign campaign = newCampaign();
        recipientService.addRecipient(campaign.getId(), "once@example.invalid", null);

        assertThatExceptionOfType(DuplicateRecipientException.class)
                .isThrownBy(() -> recipientService.addRecipient(campaign.getId(), "ONCE@example.invalid", null))
                .satisfies(ex -> assertThat(ex.getEmail()).isEqualTo("ONCE@example.invalid"));

        assertThat(recipientRepository.countByCampaign(campaign)).isEqualTo(1);
    }

    // Fall #11: dieselbe Adresse darf in zwei verschiedenen Kampagnen angelegt werden.
    @Test
    void sameEmailAllowedInDifferentCampaigns() {
        Campaign first = newCampaign();
        Campaign second = newCampaign();

        recipientService.addRecipient(first.getId(), "shared@example.invalid", null);
        recipientService.addRecipient(second.getId(), "shared@example.invalid", null);

        assertThat(recipientRepository.countByCampaign(first)).isEqualTo(1);
        assertThat(recipientRepository.countByCampaign(second)).isEqualTo(1);
    }

    @Test
    void statsCountsRecipientsByStatus() {
        Campaign campaign = newCampaign();
        recipientService.importRecipients(campaign.getId(),
                "s1@example.invalid\ns2@example.invalid\ns3@example.invalid");

        RecipientStats stats = recipientService.stats(campaign.getId());

        assertThat(stats.total()).isEqualTo(3);
        assertThat(stats.notSent()).isEqualTo(3);
        assertThat(stats.sent()).isZero();
        assertThat(stats.failed()).isZero();
    }
}
