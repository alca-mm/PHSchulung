package de.internal.awareness.web;

import de.internal.awareness.config.AppMailProperties;
import de.internal.awareness.config.AppTrackingProperties;
import de.internal.awareness.contact.ContactService;
import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailBatchRepository;
import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.mail.MailDeliveryRepository;
import de.internal.awareness.tracking.MailTrackingService;
import de.internal.awareness.support.RecordedMimeMail;
import de.internal.awareness.support.RecordingJavaMailSender;
import de.internal.awareness.support.TestMailConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * MVC-Tests fuer den Tracking-Teil des Composers und die Batch-Detailseite: Composer-Anzeige der
 * Tracking-Basis-URL, individueller Trainingslink je Empfaenger beim Versand, Batch-Detailseite mit
 * Klick-Auswertung, sowie der Nachweis, dass KEINE Tokens/Token-Hashes im Admin-HTML erscheinen.
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
class MailComposerTrackingWebTest {

    private static final String BASE_URL = "https://training.example.invalid";

    @TempDir
    static Path generatedDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.files.generated-dir", () -> generatedDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContactService contactService;

    @Autowired
    private AppMailProperties appMailProperties;

    @Autowired
    private AppTrackingProperties appTrackingProperties;

    @Autowired
    private RecordingJavaMailSender recordingJavaMailSender;

    @Autowired
    private MailBatchRepository mailBatchRepository;

    @Autowired
    private MailDeliveryRepository mailDeliveryRepository;

    @Autowired
    private MailTrackingService mailTrackingService;

    @BeforeEach
    void setUp() {
        recordingJavaMailSender.reset();
        appMailProperties.setLiveSendEnabled(true);
        appMailProperties.setAllowedSenders(List.of("training@example.invalid"));
        appMailProperties.setDefaultSender("training@example.invalid");
        appMailProperties.setDefaultSenderName("IT Security");
        appMailProperties.setAllowedRecipientDomains(List.of());
        appTrackingProperties.setBaseUrl(BASE_URL);
    }

    private Long contact(String email) {
        return contactService.addContact(email, "Name " + email).getId();
    }

    private static String tokenFromBody(String body) {
        int idx = body.lastIndexOf("/t/");
        return body.substring(idx + 3).trim();
    }

    private MailBatch latestBatch() {
        return mailBatchRepository.findAllByOrderByCreatedAtDescIdDesc().get(0);
    }

    // Composer-Anzeige (das Sendeformular mit der Tracking-Option erscheint nur bei vorhandenen Empfaengern)
    @Test
    void composePageShowsTrackingBaseUrlAndCheckbox() throws Exception {
        contact("a@example.invalid");
        mockMvc.perform(get("/mail"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Individuellen Trainingslink einfügen")))
                .andExpect(content().string(containsString(BASE_URL)))
                .andExpect(content().string(not(containsString("DoNotLeakThisSecret123"))));
    }

    // #22 (web): jeder Empfaenger erhaelt einen individuellen Link
    @Test
    void sendWithTrackingInsertsIndividualLinks() throws Exception {
        Long a = contact("a@example.invalid");
        Long b = contact("b@example.invalid");

        mockMvc.perform(post("/mail/send").with(csrf())
                        .param("subject", "Betreff").param("body", "Text")
                        .param("contactIds", a.toString(), b.toString())
                        .param("confirmed", "true")
                        .param("insertTrackingLink", "true"))
                .andExpect(status().is3xxRedirection());

        List<RecordedMimeMail> mails = recordingJavaMailSender.getSentMimeMails();
        assertThat(mails).hasSize(2);
        String t1 = tokenFromBody(mails.get(0).body());
        String t2 = tokenFromBody(mails.get(1).body());
        assertThat(mails.get(0).body()).contains(BASE_URL + "/t/");
        assertThat(t1).isNotEqualTo(t2);
    }

    // #42: Batch-Detailseite funktioniert
    @Test
    void historyDetailPageWorks() throws Exception {
        Long a = contact("a@example.invalid");
        mockMvc.perform(post("/mail/send").with(csrf())
                .param("subject", "Wichtiger Betreff").param("body", "Text")
                .param("contactIds", a.toString()).param("confirmed", "true"));

        MailBatch batch = latestBatch();
        mockMvc.perform(get("/mail/history/{id}", batch.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("mail/history-detail"))
                .andExpect(content().string(containsString("a@example.invalid")))
                .andExpect(content().string(containsString("Wichtiger Betreff")));
    }

    // #43 + #44: Klickanzahl und erster/letzter Klick
    @Test
    void historyDetailShowsClickCountsAfterClicks() throws Exception {
        Long a = contact("a@example.invalid");
        mockMvc.perform(post("/mail/send").with(csrf())
                .param("subject", "Betreff").param("body", "Text")
                .param("contactIds", a.toString()).param("confirmed", "true")
                .param("insertTrackingLink", "true"));

        String token = tokenFromBody(recordingJavaMailSender.getSentMimeMails().get(0).body());
        mockMvc.perform(get("/t/{token}", token)).andExpect(status().isOk());
        mockMvc.perform(get("/t/{token}", token)).andExpect(status().isOk());

        MailBatch batch = latestBatch();
        MvcResult result = mockMvc.perform(get("/mail/history/{id}", batch.getId()))
                .andExpect(status().isOk()).andReturn();

        MailTrackingService.BatchTracking summary =
                (MailTrackingService.BatchTracking) result.getModelAndView().getModel().get("summary");
        assertThat(summary.totalClicks()).isEqualTo(2L);
        assertThat(summary.respondingRecipients()).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        List<MailTrackingService.DeliveryTracking> rows =
                (List<MailTrackingService.DeliveryTracking>) result.getModelAndView().getModel().get("rows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).clickCount()).isEqualTo(2L);
        assertThat(rows.get(0).clicked()).isTrue();
        assertThat(rows.get(0).firstClick()).isNotNull();
        assertThat(rows.get(0).lastClick()).isNotNull();
        assertThat(rows.get(0).firstClick()).isBeforeOrEqualTo(rows.get(0).lastClick());
    }

    // #45 + #46: keine Token/Token-Hashes im Admin-HTML
    @Test
    void historyDetailHidesTokenAndHash() throws Exception {
        Long a = contact("a@example.invalid");
        mockMvc.perform(post("/mail/send").with(csrf())
                .param("subject", "Betreff").param("body", "Text")
                .param("contactIds", a.toString()).param("confirmed", "true")
                .param("insertTrackingLink", "true"));

        String token = tokenFromBody(recordingJavaMailSender.getSentMimeMails().get(0).body());
        MailBatch batch = latestBatch();
        MailDelivery delivery = mailDeliveryRepository.findByBatch(batch).get(0);
        String hash = delivery.getTrackingTokenHash();

        mockMvc.perform(get("/mail/history/{id}", batch.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(hash))))
                .andExpect(content().string(not(containsString(token))));
    }

    // #47: unbekannte Batch-Id -> 404
    @Test
    void unknownBatchReturnsNotFound() throws Exception {
        mockMvc.perform(get("/mail/history/{id}", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }
}
