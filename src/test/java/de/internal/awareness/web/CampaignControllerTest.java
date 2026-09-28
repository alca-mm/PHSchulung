package de.internal.awareness.web;

import de.internal.awareness.campaign.Campaign;
import de.internal.awareness.campaign.CampaignService;
import de.internal.awareness.config.AppMailProperties;
import de.internal.awareness.recipient.RecipientRegistration;
import de.internal.awareness.recipient.RecipientService;
import de.internal.awareness.recipient.RecipientStats;
import de.internal.awareness.support.RecordingJavaMailSender;
import de.internal.awareness.support.TestMailConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * MVC-Tests der Weboberflaeche (Faelle 29-40). Voller Stack ueber MockMvc gegen die isolierte SQLite-DB,
 * mit {@link RecordingJavaMailSender} statt echtem SMTP. Prueft u. a.: Uebersicht/Detailseiten,
 * Bulk-Import, Versandbutton + Bestaetigungspflicht, Zusammenfassung/Teilfehler sowie dass KEINE
 * Tracking-Hashes/-Tokens und KEINE SMTP-Secrets im HTML erscheinen.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestMailConfig.class)
@TestPropertySource(properties = {
        "spring.mail.host=smtp.example.invalid",
        "spring.mail.password=DoNotLeakThisSecret123"
})
@org.springframework.test.context.TestExecutionListeners(listeners = org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener.class, mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@WithMockUser(username = "test-admin", roles = "ADMIN")
class CampaignControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CampaignService campaignService;

    @Autowired
    private RecipientService recipientService;

    @Autowired
    private AppMailProperties appMailProperties;

    @Autowired
    private RecordingJavaMailSender recordingJavaMailSender;

    @BeforeEach
    void setUp() {
        recordingJavaMailSender.reset();
        // Standardmaessig versandbereit: Live-Send an, Absender erlaubt, keine Empfaenger-Domainbeschraenkung.
        appMailProperties.setLiveSendEnabled(true);
        appMailProperties.setAllowedSenders(List.of("training@example.invalid"));
        appMailProperties.setAllowedRecipientDomains(List.of());
    }

    private Campaign readyCampaign() {
        return campaignService.create("Testkampagne", "intern", "IT Security",
                "training@example.invalid", "Wichtiger Betreff", "Hallo, dies ist ein Test.");
    }

    // 29
    @Test
    void campaignOverviewWorks() throws Exception {
        readyCampaign();
        mockMvc.perform(get("/campaigns"))
                .andExpect(status().isOk())
                .andExpect(view().name("campaigns/list"))
                .andExpect(content().string(containsString("Kampagnen")))
                .andExpect(content().string(containsString("Testkampagne")));
    }

    // 30
    @Test
    void campaignCreateFormWorks() throws Exception {
        mockMvc.perform(get("/campaigns/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("campaigns/new"))
                .andExpect(content().string(containsString("Absender-E-Mail")))
                .andExpect(content().string(containsString("E-Mail-Text")));
    }

    // 31
    @Test
    void campaignDetailPageWorks() throws Exception {
        Campaign campaign = readyCampaign();
        mockMvc.perform(get("/campaigns/{id}", campaign.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("campaigns/detail"))
                .andExpect(content().string(containsString("Testkampagne")))
                .andExpect(content().string(containsString("Wichtiger Betreff")));
    }

    // 32
    @Test
    void recipientBulkFormWorks() throws Exception {
        Campaign campaign = readyCampaign();
        mockMvc.perform(post("/campaigns/{id}/recipients/bulk", campaign.getId()).with(csrf())
                        .param("text", "anna@example.invalid\nMax Mustermann <max@example.invalid>"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/campaigns/" + campaign.getId()))
                .andExpect(flash().attribute("flashSuccess", containsString("2 Empfänger hinzugefügt")));

        assertThat(recipientService.stats(campaign.getId()).total()).isEqualTo(2L);
    }

    // 33
    @Test
    void sendButtonIsShownWhenReady() throws Exception {
        Campaign campaign = readyCampaign();
        recipientService.addRecipient(campaign.getId(), "a@example.invalid", null);
        mockMvc.perform(get("/campaigns/{id}", campaign.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("An alle Empfänger senden")));
    }

    // 34
    @Test
    void recipientCountIsRenderedCorrectly() throws Exception {
        Campaign campaign = readyCampaign();
        recipientService.addRecipient(campaign.getId(), "a@example.invalid", null);
        recipientService.addRecipient(campaign.getId(), "b@example.invalid", null);
        recipientService.addRecipient(campaign.getId(), "c@example.invalid", null);

        MvcResult result = mockMvc.perform(get("/campaigns/{id}", campaign.getId()))
                .andExpect(status().isOk())
                .andReturn();
        RecipientStats stats = (RecipientStats) result.getModelAndView().getModel().get("stats");
        assertThat(stats.total()).isEqualTo(3L);
        assertThat(stats.notSent()).isEqualTo(3L);
    }

    // 35
    @Test
    void sendWithoutConfirmationCheckboxIsRejected() throws Exception {
        Campaign campaign = readyCampaign();
        recipientService.addRecipient(campaign.getId(), "a@example.invalid", null);

        mockMvc.perform(post("/campaigns/{id}/send", campaign.getId()).with(csrf())) // no 'confirmed' param
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/campaigns/" + campaign.getId()))
                .andExpect(flash().attributeExists("flashError"));

        assertThat(recordingJavaMailSender.getSentCount()).isZero();
    }

    // 36
    @Test
    void successfulBatchShowsSummary() throws Exception {
        Campaign campaign = readyCampaign();
        recipientService.addRecipient(campaign.getId(), "a@example.invalid", null);
        recipientService.addRecipient(campaign.getId(), "b@example.invalid", null);

        mockMvc.perform(post("/campaigns/{id}/send", campaign.getId()).with(csrf()).param("confirmed", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/campaigns/" + campaign.getId()))
                .andExpect(flash().attribute("flashSuccess", containsString("erfolgreich versendet")));

        assertThat(recordingJavaMailSender.getSentCount()).isEqualTo(2);
    }

    // 37
    @Test
    void failedRecipientsAreShownUnderstandably() throws Exception {
        Campaign campaign = readyCampaign();
        recipientService.addRecipient(campaign.getId(), "ok@example.invalid", null);
        recipientService.addRecipient(campaign.getId(), "boom@example.invalid", null);
        recordingJavaMailSender.failForRecipients("boom@example.invalid");

        mockMvc.perform(post("/campaigns/{id}/send", campaign.getId()).with(csrf()).param("confirmed", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashFailedEmails"))
                .andExpect(flash().attribute("flashSuccess", containsString("fehlgeschlagen")));
    }

    // 38 + 40
    @Test
    void noTrackingHashOrPlaintextTokenAppearsInHtml() throws Exception {
        Campaign campaign = readyCampaign();
        RecipientRegistration reg = recipientService.addRecipient(campaign.getId(), "a@example.invalid", "Alice");
        String hash = reg.recipient().getTrackingTokenHash();
        String token = reg.plaintextToken();

        MvcResult result = mockMvc.perform(get("/campaigns/{id}", campaign.getId()))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString();
        assertThat(html).doesNotContain(hash);
        assertThat(html).doesNotContain(token);
    }

    // 39
    @Test
    void noSmtpSecretAppearsInHtml() throws Exception {
        Campaign campaign = readyCampaign();
        recipientService.addRecipient(campaign.getId(), "a@example.invalid", null);

        MvcResult result = mockMvc.perform(get("/campaigns/{id}", campaign.getId()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("DoNotLeakThisSecret123");
    }

    // Zusatz: Einstieg leitet auf die Uebersicht.
    @Test
    void rootRedirectsToCampaigns() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/campaigns"));
    }

    // Zusatz: unbekannte Kampagne -> kontrollierte 404-Seite (WebExceptionHandler).
    @Test
    void unknownCampaignReturnsNotFound() throws Exception {
        mockMvc.perform(get("/campaigns/{id}", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    // Zusatz: bei deaktiviertem Live-Send zeigt die Detailseite den Testmodus-Hinweis und keinen Sendebutton.
    @Test
    void detailShowsTestmodeBannerAndNoSendButtonWhenLiveSendDisabled() throws Exception {
        appMailProperties.setLiveSendEnabled(false);
        Campaign campaign = readyCampaign();
        recipientService.addRecipient(campaign.getId(), "a@example.invalid", null);

        mockMvc.perform(get("/campaigns/{id}", campaign.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Testmodus / echter Versand deaktiviert")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("An alle Empfänger senden"))));
    }
}
