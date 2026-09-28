package de.internal.awareness.web;

import de.internal.awareness.config.AppMailProperties;
import de.internal.awareness.contact.ContactService;
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
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * MVC-Tests des globalen E-Mail-Composers. Voller Stack ueber MockMvc gegen die isolierte SQLite-DB, mit
 * {@link RecordingJavaMailSender} statt echtem SMTP. Prueft u. a.: Composer-Seite und Absenderanzeige,
 * Bestaetigungspflicht vor dem Versand, erfolgreicher Versand samt Historie, serverseitige Ablehnung
 * fehlender/manipulierter Auswahl, Validierung sowie den Testmodus-Hinweis. Zusaetzlich wird geprueft, dass
 * KEIN SMTP-Secret im HTML erscheint.
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
class MailComposerControllerTest {

    @TempDir
    static Path generatedDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.files.generated-dir", () -> generatedDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContactService contactService;

    @Autowired
    private AppMailProperties appMailProperties;

    @Autowired
    private RecordingJavaMailSender recordingJavaMailSender;

    @BeforeEach
    void setUp() {
        recordingJavaMailSender.reset();
        // Standardmaessig versandbereit: Live-Send an, gueltiger konfigurierter Absender, keine Empfaenger-Domainbeschraenkung.
        appMailProperties.setLiveSendEnabled(true);
        appMailProperties.setAllowedSenders(List.of("training@example.invalid"));
        appMailProperties.setDefaultSender("training@example.invalid");
        appMailProperties.setDefaultSenderName("IT Security");
        appMailProperties.setAllowedRecipientDomains(List.of());
    }

    @Test
    void composePageShowsSenderAndNoSecret() throws Exception {
        String html = mockMvc.perform(get("/mail"))
                .andExpect(status().isOk())
                .andExpect(view().name("mail/compose"))
                .andExpect(content().string(containsString("training@example.invalid")))
                .andReturn().getResponse().getContentAsString();
        // Es darf NIE ein SMTP-Secret im HTML erscheinen.
        assertThat(html).doesNotContain("DoNotLeakThisSecret123");
    }

    @Test
    void sendWithoutConfirmationIsRejected() throws Exception {
        Long id = contactService.addContact("a@example.invalid", "Alice").getId();

        mockMvc.perform(post("/mail/send").with(csrf())
                        .param("subject", "Betreff")
                        .param("body", "Text")
                        .param("contactIds", id.toString())) // kein 'confirmed'
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mail"))
                .andExpect(flash().attributeExists("flashError"));

        assertThat(recordingJavaMailSender.getSentMimeCount()).isZero();
    }

    @Test
    void confirmedSendDeliversOneMail() throws Exception {
        Long id = contactService.addContact("a@example.invalid", "Alice").getId();

        mockMvc.perform(post("/mail/send").with(csrf())
                        .param("subject", "Betreff")
                        .param("body", "Text")
                        .param("contactIds", id.toString())
                        .param("confirmed", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mail"))
                .andExpect(flash().attributeExists("flashSuccess"));

        assertThat(recordingJavaMailSender.getSentMimeCount()).isEqualTo(1);
    }

    @Test
    void confirmedSendWithoutRecipientsIsBlocked() throws Exception {
        mockMvc.perform(post("/mail/send").with(csrf())
                        .param("subject", "Betreff")
                        .param("body", "Text")
                        .param("confirmed", "true")) // keine contactIds
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mail"))
                .andExpect(flash().attributeExists("flashError"));

        assertThat(recordingJavaMailSender.getSentMimeCount()).isZero();
    }

    @Test
    void confirmedSendWithManipulatedContactIdIsRejected() throws Exception {
        mockMvc.perform(post("/mail/send").with(csrf())
                        .param("subject", "Betreff")
                        .param("body", "Text")
                        .param("contactIds", "999999")
                        .param("confirmed", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mail"))
                .andExpect(flash().attributeExists("flashError"));

        assertThat(recordingJavaMailSender.getSentMimeCount()).isZero();
    }

    @Test
    void blankSubjectFailsValidationAndSendsNothing() throws Exception {
        Long id = contactService.addContact("a@example.invalid", "Alice").getId();

        mockMvc.perform(post("/mail/send").with(csrf())
                        .param("subject", "")
                        .param("body", "Text")
                        .param("contactIds", id.toString())
                        .param("confirmed", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("mail/compose"));

        assertThat(recordingJavaMailSender.getSentMimeCount()).isZero();
    }

    @Test
    void historyShowsSentSubjectAfterSuccessfulSend() throws Exception {
        Long id = contactService.addContact("a@example.invalid", "Alice").getId();

        mockMvc.perform(post("/mail/send").with(csrf())
                        .param("subject", "Verlaufsbetreff")
                        .param("body", "Text")
                        .param("contactIds", id.toString())
                        .param("confirmed", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mail"))
                .andExpect(flash().attributeExists("flashSuccess"));

        mockMvc.perform(get("/mail/history"))
                .andExpect(status().isOk())
                .andExpect(view().name("mail/history"))
                .andExpect(content().string(containsString("Verlaufsbetreff")));
    }

    @Test
    void composePageShowsTestmodeWhenLiveSendDisabled() throws Exception {
        appMailProperties.setLiveSendEnabled(false);

        mockMvc.perform(get("/mail"))
                .andExpect(status().isOk())
                .andExpect(view().name("mail/compose"))
                .andExpect(content().string(containsString("Testmodus")));
    }
}
