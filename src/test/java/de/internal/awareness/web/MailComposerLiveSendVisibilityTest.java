package de.internal.awareness.web;

import de.internal.awareness.config.AppMailProperties;
import de.internal.awareness.config.AppTrackingProperties;
import de.internal.awareness.contact.ContactService;
import de.internal.awareness.mail.MailBatchRepository;
import de.internal.awareness.mail.MailDeliveryRepository;
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
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Regressionstests fuer den Bugfix "Composer-Sichtbarkeit vs. Versandbereitschaft".
 *
 * <p>Kernaussage: {@code APP_MAIL_LIVE_SEND_ENABLED=false} (Testmodus) darf ausschliesslich den ECHTEN
 * Versand blockieren, nicht den Composer. Der Composer (Betreff, Text, Anhangauswahl, Empfaengerauswahl,
 * "Alle auswaehlen", Trainingslink-Option, Vorschau) muss vollstaendig nutzbar bleiben; nur der echte
 * Sende-Button ist an die Versandbereitschaft ({@code readiness.ready}) gekoppelt. Zusaetzlich wird
 * geprueft, dass der Composer auch bei leerer Kontaktliste bzw. leerer Dateibibliothek sichtbar bleibt und
 * dass die CSRF-Absicherung des Formulars erhalten bleibt.</p>
 *
 * <p>Der {@code RecordingJavaMailSender} belegt, dass die Vorschau bei deaktiviertem Live-Send NICHTS
 * versendet; die Repositories belegen, dass NICHTS persistiert wird. Es werden ausschliesslich fiktive
 * {@code example.invalid}-Werte verwendet.</p>
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
class MailComposerLiveSendVisibilityTest {

    private static final String SEND_BUTTON_TEXT = "E-Mail an ausgewählte Empfänger senden";
    private static final String EMPTY_RECIPIENTS_HINT = "Noch keine Empfänger gespeichert";

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
    private AppTrackingProperties appTrackingProperties;

    @Autowired
    private RecordingJavaMailSender recordingJavaMailSender;

    @Autowired
    private MailBatchRepository mailBatchRepository;

    @Autowired
    private MailDeliveryRepository mailDeliveryRepository;

    @BeforeEach
    void setUp() {
        recordingJavaMailSender.reset();
        // Absender vollstaendig und erlaubt konfigurieren, SMTP-Host per Property gesetzt: Damit ist die
        // Bereitschaft NUR noch vom Live-Send-Schalter abhaengig. Standard hier: Testmodus (live-send=false).
        appMailProperties.setDefaultSender("training@example.invalid");
        appMailProperties.setDefaultSenderName("IT Security");
        appMailProperties.setAllowedSenders(List.of("training@example.invalid"));
        appMailProperties.setAllowedRecipientDomains(List.of());
        appMailProperties.setLiveSendEnabled(false);
    }

    private Long contact(String email) {
        return contactService.addContact(email, "Name " + email).getId();
    }

    // 1-3, 6, 7: Betreff-, Text-, Anhang-Auswahl-, Trainingslink- und Vorschau-Elemente sind bei
    // deaktiviertem Live-Send sichtbar; das Formular wird ueberhaupt gerendert.
    @Test
    void composerFieldsAreVisibleWhenLiveSendDisabled() throws Exception {
        contact("a@example.invalid");

        String html = mockMvc.perform(get("/mail"))
                .andExpect(status().isOk())
                .andExpect(view().name("mail/compose"))
                // Formular selbst wird gerendert (Sende-Ziel vorhanden).
                .andExpect(content().string(containsString("action=\"/mail/send\"")))
                // Betreff-Feld
                .andExpect(content().string(containsString("id=\"subject\"")))
                // Text-Feld
                .andExpect(content().string(containsString("id=\"body\"")))
                // Anhang-Auswahl inkl. "Kein Anhang"
                .andExpect(content().string(containsString("id=\"generatedFileId\"")))
                .andExpect(content().string(containsString("Kein Anhang")))
                // Trainingslink-Option
                .andExpect(content().string(containsString("id=\"trackingCb\"")))
                .andExpect(content().string(containsString("Individuellen Trainingslink einfügen")))
                // Vorschau-Button (Dry-Run) bleibt verfuegbar
                .andExpect(content().string(containsString("/mail/preview")))
                .andExpect(content().string(containsString("Vorschau")))
                .andReturn().getResponse().getContentAsString();

        // Kein SMTP-Secret im HTML.
        assertThat(html).doesNotContain("DoNotLeakThisSecret123");
    }

    // 4, 5: Empfaengerauswahl (Checkboxen) und "Alle auswaehlen/abwaehlen" sind bei live-send=false sichtbar.
    @Test
    void recipientSelectionIsVisibleWhenLiveSendDisabled() throws Exception {
        contact("recipient@example.invalid");

        mockMvc.perform(get("/mail"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("recipient@example.invalid")))
                .andExpect(content().string(containsString("name=\"contactIds\"")))
                .andExpect(content().string(containsString("id=\"selectAllBtn\"")))
                .andExpect(content().string(containsString("id=\"deselectAllBtn\"")));
    }

    // 8: Der ECHTE Sende-Button (und die Sende-Bestaetigung) sind bei deaktiviertem Live-Send nicht vorhanden;
    // stattdessen erscheinen Testmodus-Hinweis und Blocker-Begruendung.
    @Test
    void realSendButtonIsAbsentWhenLiveSendDisabled() throws Exception {
        contact("a@example.invalid");

        mockMvc.perform(get("/mail"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"sendBtn\""))))
                .andExpect(content().string(not(containsString(SEND_BUTTON_TEXT))))
                .andExpect(content().string(not(containsString("id=\"confirmCb\""))))
                // Der Nutzer sieht, WARUM nicht gesendet werden kann. Die Zeichenkette
                // "APP_MAIL_LIVE_SEND_ENABLED=false" erscheint NUR im Testmodus-Banner bzw. in der
                // Blocker-Begruendung (nicht im statischen HTML-Kommentar) und ist daher aussagekraeftig.
                .andExpect(content().string(containsString("APP_MAIL_LIVE_SEND_ENABLED=false")))
                .andExpect(content().string(containsString("Versand aktuell nicht möglich")));
    }

    // 9 + 10: Die Vorschau funktioniert bei deaktiviertem Live-Send, versendet aber NICHTS und persistiert NICHTS.
    @Test
    void previewWorksAndSendsNothingWhenLiveSendDisabled() throws Exception {
        Long a = contact("a@example.invalid");

        mockMvc.perform(post("/mail/preview").with(csrf())
                        .param("subject", "Betreff").param("body", "Text")
                        .param("contactIds", a.toString()))
                .andExpect(status().isOk())
                .andExpect(view().name("mail/preview"));

        assertThat(recordingJavaMailSender.getSentCount()).isZero();
        assertThat(recordingJavaMailSender.getSentMimeCount()).isZero();
        assertThat(mailBatchRepository.count()).isZero();
        assertThat(mailDeliveryRepository.count()).isZero();
    }

    // 11: Bei leerer Kontaktliste bleibt der Composer sichtbar und zeigt einen Empty-State mit Link auf /recipients;
    // es werden keine Empfaenger-Checkboxen gerendert.
    @Test
    void composerVisibleWithEmptyContactsShowsEmptyState() throws Exception {
        // bewusst KEIN Kontakt angelegt

        mockMvc.perform(get("/mail"))
                .andExpect(status().isOk())
                .andExpect(view().name("mail/compose"))
                // Formular (Betreff/Text) trotzdem sichtbar
                .andExpect(content().string(containsString("action=\"/mail/send\"")))
                .andExpect(content().string(containsString("id=\"subject\"")))
                // Empty-State + Verweis auf die Empfaengerverwaltung
                .andExpect(content().string(containsString(EMPTY_RECIPIENTS_HINT)))
                .andExpect(content().string(containsString("/recipients")))
                // keine Empfaenger-Checkbox vorhanden
                .andExpect(content().string(not(containsString("name=\"contactIds\""))));
    }

    // 12: Bei leerer Dateibibliothek bleibt der Composer sichtbar und "Kein Anhang" ist auswaehlbar.
    @Test
    void composerVisibleWithEmptyFileLibraryShowsKeinAnhang() throws Exception {
        contact("a@example.invalid"); // Datei-Verzeichnis (TempDir) ist leer -> keine Dateien

        mockMvc.perform(get("/mail"))
                .andExpect(status().isOk())
                .andExpect(view().name("mail/compose"))
                .andExpect(content().string(containsString("id=\"generatedFileId\"")))
                .andExpect(content().string(containsString("Kein Anhang")))
                // Empty-State fuer die Dateibibliothek inkl. optionalem "Datei erstellen"-Link.
                .andExpect(content().string(containsString("Noch keine Dateien in der Bibliothek")))
                .andExpect(content().string(containsString("/files/new")));
    }

    // 13: Bei vollstaendiger Versandbereitschaft (live-send=true) erscheinen Sende-Bestaetigung und Sende-Button.
    @Test
    void realSendButtonAppearsWhenFullyReady() throws Exception {
        appMailProperties.setLiveSendEnabled(true);
        contact("a@example.invalid");

        mockMvc.perform(get("/mail"))
                .andExpect(status().isOk())
                .andExpect(view().name("mail/compose"))
                .andExpect(content().string(containsString("id=\"sendBtn\"")))
                .andExpect(content().string(containsString(SEND_BUTTON_TEXT)))
                .andExpect(content().string(containsString("id=\"confirmCb\"")))
                // Vorschau bleibt ebenfalls verfuegbar
                .andExpect(content().string(containsString("/mail/preview")))
                // Kein Testmodus-Banner / keine Blocker-Begruendung bei aktiviertem Live-Send.
                // (Der statische HTML-Kommentar enthaelt zwar das Wort "Testmodus", nicht aber diese
                // Zeichenkette; sie ist daher ein praezises Signal fuer das tatsaechliche Banner.)
                .andExpect(content().string(not(containsString("APP_MAIL_LIVE_SEND_ENABLED=false"))))
                .andExpect(content().string(not(containsString("Versand aktuell nicht möglich"))));
    }

    // 14: Das Composer-Formular enthaelt auch im Testmodus (live-send=false) das CSRF-Token.
    @Test
    void composerFormContainsCsrfTokenWhenLiveSendDisabled() throws Exception {
        contact("a@example.invalid");

        mockMvc.perform(get("/mail"))
                .andExpect(status().isOk())
                // Composer-Formular ist gerendert ...
                .andExpect(content().string(containsString("action=\"/mail/send\"")))
                // ... und traegt das CSRF-Token.
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }
}
