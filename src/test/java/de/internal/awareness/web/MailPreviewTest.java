package de.internal.awareness.web;

import de.internal.awareness.config.AppMailProperties;
import de.internal.awareness.config.AppTrackingProperties;
import de.internal.awareness.contact.ContactService;
import de.internal.awareness.file.GeneratedFile;
import de.internal.awareness.file.GeneratedFileService;
import de.internal.awareness.mail.MailBatchRepository;
import de.internal.awareness.mail.MailDeliveryRepository;
import de.internal.awareness.support.TestMailConfig;
import de.internal.awareness.tracking.MailTrackingEventRepository;
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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * MVC-Tests fuer die Versand-VORSCHAU (Dry-Run) des Composers ({@code POST /mail/preview}). Kernnachweis:
 * Die Vorschau versendet NICHTS und persistiert NICHTS (kein MailBatch/MailDelivery/MailTrackingEvent, kein
 * Token) und rendert KEINE Secrets/Tokens (Tracking nur als Platzhalter). Zusaetzlich: Empfaenger-/Anhang-
 * Metadaten, Auto-Escaping von Betreff/Text und die kontrollierte Ablehnung manipulierter Ids.
 *
 * <p>Live-Send bleibt bewusst auf dem Default {@code false}, um zu zeigen, dass die Vorschau auch ohne
 * aktivierten echten Versand funktioniert.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestMailConfig.class)
@TestPropertySource(properties = {"spring.mail.host=smtp.example.invalid"})
@org.springframework.test.context.TestExecutionListeners(listeners = org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener.class, mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@WithMockUser(username = "test-admin", roles = "ADMIN")
class MailPreviewTest {

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
    private GeneratedFileService generatedFileService;

    @Autowired
    private AppMailProperties appMailProperties;

    @Autowired
    private AppTrackingProperties appTrackingProperties;

    @Autowired
    private MailBatchRepository mailBatchRepository;

    @Autowired
    private MailDeliveryRepository mailDeliveryRepository;

    @Autowired
    private MailTrackingEventRepository mailTrackingEventRepository;

    @BeforeEach
    void setUp() {
        // Absender bewusst gueltig konfigurieren; Live-Send bleibt Default false (Vorschau funktioniert ohne).
        appMailProperties.setDefaultSender("training@example.invalid");
        appMailProperties.setDefaultSenderName("IT Security");
        appMailProperties.setAllowedSenders(List.of("training@example.invalid"));
    }

    private Long contact(String email) {
        return contactService.addContact(email, "Name " + email).getId();
    }

    // Vorschau rendert und versendet/persistiert NICHTS.
    @Test
    void previewRendersWithoutSending() throws Exception {
        Long a = contact("a@example.invalid");

        mockMvc.perform(post("/mail/preview").with(csrf())
                        .param("subject", "Betreff").param("body", "Text")
                        .param("contactIds", a.toString()))
                .andExpect(status().isOk())
                .andExpect(view().name("mail/preview"));

        assertThat(mailBatchRepository.count()).isZero();
        assertThat(mailDeliveryRepository.count()).isZero();
        assertThat(mailTrackingEventRepository.count()).isZero();
    }

    // Die ausgewaehlten Empfaenger werden in der Vorschau angezeigt.
    @Test
    void previewShowsRecipients() throws Exception {
        Long a = contact("recipient@example.invalid");

        mockMvc.perform(post("/mail/preview").with(csrf())
                        .param("subject", "Betreff").param("body", "Text")
                        .param("contactIds", a.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("recipient@example.invalid")));
    }

    // Betreff und Text werden auto-escaped (kein HTML-Injection-Risiko).
    @Test
    void previewEscapesSubjectAndBody() throws Exception {
        Long a = contact("a@example.invalid");

        mockMvc.perform(post("/mail/preview").with(csrf())
                        .param("subject", "<script>alert(1)</script>").param("body", "Text")
                        .param("contactIds", a.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))));
    }

    // DOCX-Anhang: Downloadname und Typ erscheinen in der Vorschau.
    @Test
    void previewShowsDocxMetadata() throws Exception {
        Long a = contact("a@example.invalid");
        GeneratedFile docx = generatedFileService.createDocx("Rechnung", "rechnung", "Titel", "Untertitel", "Inhalt");

        mockMvc.perform(post("/mail/preview").with(csrf())
                        .param("subject", "Betreff").param("body", "Text")
                        .param("contactIds", a.toString())
                        .param("generatedFileId", docx.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("rechnung.docx")))
                .andExpect(content().string(containsString("DOCX")));
    }

    // XML-Anhang: Downloadname erscheint in der Vorschau.
    @Test
    void previewShowsXmlMetadata() throws Exception {
        Long a = contact("a@example.invalid");
        GeneratedFile xml = generatedFileService.createXml("Meldung", "meldung", "root", "Titel", "Inhalt");

        mockMvc.perform(post("/mail/preview").with(csrf())
                        .param("subject", "Betreff").param("body", "Text")
                        .param("contactIds", a.toString())
                        .param("generatedFileId", xml.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("meldung.xml")));
    }

    // Manipulierte Datei-Id -> kontrollierte Ablehnung (Redirect + Flash), kein 500er.
    @Test
    void previewRejectsManipulatedFileId() throws Exception {
        Long a = contact("a@example.invalid");

        mockMvc.perform(post("/mail/preview").with(csrf())
                        .param("subject", "Betreff").param("body", "Text")
                        .param("contactIds", a.toString())
                        .param("generatedFileId", "999999"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mail"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // Manipulierte Empfaenger-Id -> kontrollierte Ablehnung (Redirect + Flash).
    @Test
    void previewRejectsManipulatedContactId() throws Exception {
        mockMvc.perform(post("/mail/preview").with(csrf())
                        .param("subject", "Betreff").param("body", "Text")
                        .param("contactIds", "999999"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mail"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // Tracking-Vorschau zeigt nur einen Platzhalter, NIE einen echten Token; nichts wird persistiert.
    @Test
    void previewTrackingHasNoRealToken() throws Exception {
        Long a = contact("a@example.invalid");
        appTrackingProperties.setBaseUrl("https://training.example.invalid");

        MvcResult result = mockMvc.perform(post("/mail/preview").with(csrf())
                        .param("subject", "Betreff").param("body", "Text")
                        .param("contactIds", a.toString())
                        .param("insertTrackingLink", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;individueller-token&gt;")))
                .andReturn();

        // Es darf KEIN echter Tracking-Link (/t/ + 43-Zeichen-Base64url-Token) im HTML stehen.
        String html = result.getResponse().getContentAsString();
        assertThat(Pattern.compile("/t/[A-Za-z0-9_-]{43}").matcher(html).find()).isFalse();

        // Kein Token persistiert: keine Zustellung angelegt.
        assertThat(mailDeliveryRepository.count()).isZero();
    }

    // Vorschau funktioniert auch bei deaktiviertem Live-Send (Default false).
    @Test
    void previewWorksWithLiveSendDisabled() throws Exception {
        assertThat(appMailProperties.isLiveSendEnabled()).isFalse();
        Long a = contact("a@example.invalid");

        mockMvc.perform(post("/mail/preview").with(csrf())
                        .param("subject", "Betreff").param("body", "Text")
                        .param("contactIds", a.toString()))
                .andExpect(status().isOk())
                .andExpect(view().name("mail/preview"));
    }
}
