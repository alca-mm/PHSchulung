package de.internal.awareness.web;

import de.internal.awareness.config.AppMailProperties;
import de.internal.awareness.config.AppTrackingProperties;
import de.internal.awareness.contact.ContactService;
import de.internal.awareness.file.DocxGenerator;
import de.internal.awareness.file.DocxTrackingPersonalizer;
import de.internal.awareness.file.GeneratedFile;
import de.internal.awareness.file.GeneratedFileService;
import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailBatchRepository;
import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.mail.MailDeliveryRepository;
import de.internal.awareness.support.RecordingJavaMailSender;
import de.internal.awareness.support.TestMailConfig;
import de.internal.awareness.tracking.MailTrackingEventRepository;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
 * Regressionstests fuer die empfaengerbezogene Dokument-/Trainingszuordnung und das Reporting.
 *
 * <p>Belegt die im Feature geforderten Garantien, die bisher nur implizit abgedeckt waren:
 * <ul>
 *   <li>#4/#5 – die personalisierte DOCX-Versandkopie von Empfaenger A enthaelt ausschliesslich A's Token,
 *       niemals das Token von B (und umgekehrt);</li>
 *   <li>#17/#18 – die Tracking-URL enthaelt weder eine E-Mail-Adresse noch eine Datenbank-ID, sondern nur
 *       einen zufaelligen, undurchsichtigen Token ({@code /t/<43-Zeichen-base64url>});</li>
 *   <li>#21 – das blosse Erzeugen/Versenden (und damit spaetere Oeffnen) der Dokumente erzeugt KEIN
 *       Tracking-Ereignis; nur der bewusste Klick auf {@code /t/{token}} tut dies;</li>
 *   <li>Reporting – die Batch-Detailseite zeigt je Zustellung die zugeordnete Datei ("Datei"-Spalte),
 *       ohne Token oder Hash preiszugeben.</li>
 * </ul>
 *
 * <p>Live-Send ist aktiv, aber es wird ausschliesslich der {@link RecordingJavaMailSender} verwendet – es
 * gehen keine echten E-Mails hinaus. Es werden ausschliesslich fiktive {@code example.invalid}-Werte genutzt.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.transaction.annotation.Transactional
@Import(TestMailConfig.class)
@TestPropertySource(properties = {
        "spring.mail.host=smtp.example.invalid",
        "spring.mail.password=DoNotLeakThisSecret123"
})
@org.springframework.test.context.TestExecutionListeners(listeners = org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener.class, mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@WithMockUser(username = "test-admin", roles = "ADMIN")
class RecipientDocumentTrackingAssociationTest {

    private static final String BASE_URL = "https://training.example.invalid";
    // Bewusst maximal unterschiedliche 43-Zeichen-Token: keiner ist Teilstring des anderen.
    private static final String TOKEN_A = "A".repeat(43);
    private static final String TOKEN_B = "B".repeat(43);
    private static final String URL_A = BASE_URL + "/t/" + TOKEN_A;
    private static final String URL_B = BASE_URL + "/t/" + TOKEN_B;

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
    private RecordingJavaMailSender recordingJavaMailSender;

    @Autowired
    private MailBatchRepository mailBatchRepository;

    @Autowired
    private MailDeliveryRepository mailDeliveryRepository;

    @Autowired
    private MailTrackingEventRepository mailTrackingEventRepository;

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

    /** Extrahiert den 43-Zeichen-base64url-Token aus dem ersten {@code /t/<token>} im Text. */
    private static String tokenFromText(String text) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("/t/([A-Za-z0-9_-]{43})").matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException("Kein Trainingslink-Token im Text gefunden.");
        }
        return matcher.group(1);
    }

    /** Token aus dem sichtbaren Hyperlink der personalisierten DOCX-Versandkopie. */
    private static String docxToken(byte[] docx) throws Exception {
        return tokenFromText(docxText(docx));
    }

    private static String trackingUrlFromBody(String body) {
        int idx = body.lastIndexOf(BASE_URL + "/t/");
        String rest = body.substring(idx);
        // bis zum ersten Whitespace/Zeilenende schneiden
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            if (Character.isWhitespace(rest.charAt(i))) {
                end = i;
                break;
            }
        }
        return rest.substring(0, end);
    }

    private MailBatch latestBatch() {
        return mailBatchRepository.findAllByOrderByCreatedAtDescIdDesc().get(0);
    }

    private static String docxText(byte[] docx) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private static String relsConcatenated(byte[] docx) throws Exception {
        StringBuilder all = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().endsWith(".rels")) {
                    all.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
                }
            }
        }
        return all.toString();
    }

    // #4 + #5: DOCX von A enthaelt nur A's Token/URL, niemals B's – und umgekehrt.
    @Test
    void docxCopyNeverContainsAnotherRecipientsToken() throws Exception {
        byte[] base = new DocxGenerator().generate("Rechnung", "September", "Sehr geehrte Damen und Herren");
        DocxTrackingPersonalizer personalizer = new DocxTrackingPersonalizer();

        byte[] docxA = personalizer.withTrainingLink(base, URL_A);
        byte[] docxB = personalizer.withTrainingLink(base, URL_B);

        String textA = docxText(docxA);
        String textB = docxText(docxB);
        assertThat(textA).contains(TOKEN_A).doesNotContain(TOKEN_B);
        assertThat(textB).contains(TOKEN_B).doesNotContain(TOKEN_A);

        // Auch die (einzige externe) Hyperlink-Relationship darf nur die eigene URL enthalten.
        String relsA = relsConcatenated(docxA);
        String relsB = relsConcatenated(docxB);
        assertThat(relsA).contains(URL_A).doesNotContain(URL_B);
        assertThat(relsB).contains(URL_B).doesNotContain(URL_A);
    }

    // #17 + #18: Tracking-URL = base + /t/ + undurchsichtiger 43-Zeichen-Token; keine E-Mail, keine DB-ID.
    @Test
    void trackingUrlIsOpaqueTokenWithoutEmailOrDatabaseId() throws Exception {
        String email = "empfaenger@example.invalid";
        Long id = contact(email);

        mockMvc.perform(post("/mail/send").with(csrf())
                        .param("subject", "Betreff").param("body", "Text")
                        .param("contactIds", id.toString())
                        .param("confirmed", "true")
                        .param("insertTrackingLink", "true"))
                .andExpect(status().is3xxRedirection());

        String body = recordingJavaMailSender.getSentMimeMails().get(0).body();
        String url = trackingUrlFromBody(body);
        String afterT = url.substring(url.indexOf("/t/") + 3);

        // Nur ein zufaelliger base64url-Token folgt auf /t/ – strukturell weder E-Mail noch ID moeglich.
        assertThat(afterT).matches("[A-Za-z0-9_-]{43}");
        assertThat(url).isEqualTo(BASE_URL + "/t/" + afterT);
        // #17: keine E-Mail-Adresse in der URL
        assertThat(url).doesNotContain("@");
        assertThat(url).doesNotContain(email);
        assertThat(url.toLowerCase()).doesNotContain("email=");
        // #18: keine Datenbank-ID / kein personenbezogener Pfad
        assertThat(url.toLowerCase()).doesNotContain("recipient/");
        assertThat(url.toLowerCase()).doesNotContain("contact/");
        assertThat(url.toLowerCase()).doesNotContain("id=");
    }

    // #21 (+ Bestaetigung click-only): Erzeugen/Versenden der Dokumente erzeugt KEIN Ereignis; nur der Klick.
    @Test
    void preparingAndSendingDocumentsCreatesNoEventOnlyDeliberateClickDoes() throws Exception {
        Long id = contact("a@example.invalid");
        GeneratedFile docx = generatedFileService.createDocx("Rechnung", "rechnung", "Titel", "Untertitel", "Inhalt");

        mockMvc.perform(post("/mail/send").with(csrf())
                        .param("subject", "Betreff").param("body", "Text")
                        .param("contactIds", id.toString())
                        .param("generatedFileId", docx.getId().toString())
                        .param("confirmed", "true")
                        .param("insertTrackingLink", "true"))
                .andExpect(status().is3xxRedirection());

        // Nach dem Erzeugen der personalisierten DOCX und dem Versand: NOCH KEIN Ereignis
        // (das blosse Erzeugen/Oeffnen eines Dokuments loest nichts aus).
        assertThat(mailTrackingEventRepository.count()).isZero();

        // Der individuelle Link steckt sichtbar in der DOCX-Versandkopie des Empfaengers.
        byte[] docxBytes = recordingJavaMailSender.getSentMimeMails().get(0).attachments().get(0).bytes();
        String token = docxToken(docxBytes);
        mockMvc.perform(get("/t/{token}", token)).andExpect(status().isOk());

        // Erst der bewusste Klick erzeugt genau ein Ereignis.
        assertThat(mailTrackingEventRepository.count()).isEqualTo(1);
    }

    // Reporting: Batch-Detailseite zeigt je Zustellung die Datei-Spalte, ohne Token/Hash preiszugeben.
    @Test
    void historyDetailShowsAttachmentFilePerDeliveryWithoutLeakingToken() throws Exception {
        Long id = contact("a@example.invalid");
        GeneratedFile docx = generatedFileService.createDocx("Rechnung", "rechnung", "Titel", "Untertitel", "Inhalt");

        mockMvc.perform(post("/mail/send").with(csrf())
                        .param("subject", "Betreff").param("body", "Text")
                        .param("contactIds", id.toString())
                        .param("generatedFileId", docx.getId().toString())
                        .param("confirmed", "true")
                        .param("insertTrackingLink", "true"))
                .andExpect(status().is3xxRedirection());

        MailBatch batch = latestBatch();
        byte[] docxBytes = recordingJavaMailSender.getSentMimeMails().get(0).attachments().get(0).bytes();
        String token = docxToken(docxBytes);
        MailDelivery delivery = mailDeliveryRepository.findByBatch(batch).get(0);
        String hash = delivery.getTrackingTokenHash();

        mockMvc.perform(get("/mail/history/{id}", batch.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("mail/history-detail"))
                // NEUE Spalte "Datei" in der Zustellungstabelle (nicht das Nav-Wort "Dateien").
                .andExpect(content().string(containsString("<th>Datei</th>")))
                // Der zugeordnete Dateiname erscheint GENAU in der Zustellungszelle (<td>), nicht nur in der
                // Batch-Kopfzeile (die den Anhang als <span> rendert) – beweist die Pro-Zustellung-Spalte.
                .andExpect(content().string(containsString("<td>rechnung.docx</td>")))
                // Weiterhin KEIN Token und KEIN Hash im Admin-HTML.
                .andExpect(content().string(not(containsString(hash))))
                .andExpect(content().string(not(containsString(token))));
    }
}
