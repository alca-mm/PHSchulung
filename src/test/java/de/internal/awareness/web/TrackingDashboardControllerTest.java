package de.internal.awareness.web;

import de.internal.awareness.contact.Contact;
import de.internal.awareness.contact.ContactRepository;
import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailBatchRepository;
import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.mail.MailDeliveryRepository;
import de.internal.awareness.tracking.MailTrackingEvent;
import de.internal.awareness.tracking.MailTrackingEventRepository;
import de.internal.awareness.tracking.TrackingDashboardService;
import de.internal.awareness.tracking.TrackingEventType;
import de.internal.awareness.tracking.TrackingTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Full-Stack-MVC-Tests des Tracking-Dashboards ({@link TrackingDashboardController}) ueber MockMvc gegen die
 * isolierte SQLite-DB. Prueft Zugriffsschutz (anonym -&gt; Login, Admin -&gt; 200), Anzeige der Seed-Daten
 * (Empfaenger, Datei, Versandvorgang, Klickzahlen, "Ja"-Badge), den Filter (Name/E-Mail, Versandvorgang,
 * nur ausgeloeste) samt global bleibender Zusammenfassung, den Nav-Link sowie die zentrale Datenschutz-
 * Garantie, dass WEDER der Klartext-Token NOCH sein SHA-256-Hash im Admin-HTML erscheinen.
 *
 * <p>Die {@code app.files.generated-dir}-Property wird auf ein {@link TempDir} umgebogen, damit das echte
 * {@code ./data}-Verzeichnis nie beruehrt wird. Es werden ausschliesslich fiktive {@code example.invalid}-Werte
 * verwendet.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.test.context.TestExecutionListeners(
        listeners = org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener.class,
        mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class TrackingDashboardControllerTest {

    // Bekannter Klartext-Token, der NIEMALS gerendert werden darf (nur der Hash wird gespeichert).
    private static final String RAW_TOKEN = "RAWtokenDoNotRender_0123456789abcdefghijABC";
    // Der zugehoerige SHA-256-Hex-Hash, der ebenfalls nie im Admin-HTML erscheinen darf.
    private static final String TOKEN_HASH = TrackingTokens.hash(RAW_TOKEN);

    @TempDir
    static Path generatedDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.files.generated-dir", () -> generatedDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private MailBatchRepository mailBatchRepository;

    @Autowired
    private MailDeliveryRepository mailDeliveryRepository;

    @Autowired
    private MailTrackingEventRepository mailTrackingEventRepository;

    /** Legt eine tracking-faehige Zustellung (mit Hash) an und optional {@code clicks} Klickereignisse. */
    private MailDelivery seedDelivery(String name, String email, String subject, String filename,
                                      String tokenHash, int clicks) {
        Contact contact = contactRepository.save(new Contact(email, name));
        MailBatch batch = mailBatchRepository.save(new MailBatch(
                subject, "Text", "training@example.invalid", "IT Security", null, filename, 1));
        MailDelivery delivery = new MailDelivery(batch, contact, tokenHash);
        delivery.recordSent(Instant.parse("2026-09-01T10:15:30Z"));
        delivery = mailDeliveryRepository.save(delivery);
        Instant base = Instant.parse("2026-09-02T08:00:00Z");
        for (int i = 0; i < clicks; i++) {
            mailTrackingEventRepository.save(
                    new MailTrackingEvent(delivery, TrackingEventType.LINK_CLICK, base.plusSeconds(60L * i)));
        }
        return delivery;
    }

    @SuppressWarnings("unchecked")
    private List<TrackingDashboardService.Row> rowsOf(MvcResult result) {
        return (List<TrackingDashboardService.Row>) result.getModelAndView().getModel().get("rows");
    }

    private TrackingDashboardService.Summary summaryOf(MvcResult result) {
        return (TrackingDashboardService.Summary) result.getModelAndView().getModel().get("summary");
    }

    // #14: ohne Login -> kein 200, Redirect zur Loginseite (wie AdminSecurityTest fuer geschuetzte Routen).
    @Test
    void anonymousAccessRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/tracking"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // #15: als Admin -> 200 und View "tracking/dashboard".
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void adminSeesDashboard() throws Exception {
        mockMvc.perform(get("/tracking"))
                .andExpect(status().isOk())
                .andExpect(view().name("tracking/dashboard"))
                .andExpect(content().string(containsString("Tracking")));
    }

    // #16-20 + "Ja": Seed-Daten (Name/E-Mail/Datei/Batch/Klickzahl/erster+letzter Klick) erscheinen im HTML.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void dashboardRendersSeededTrackingData() throws Exception {
        seedDelivery("Alice Beispiel", "alice@example.invalid", "Quartalsbericht Q3",
                "bericht.docx", TrackingTokens.hash("alice-token"), 2);

        MvcResult result = mockMvc.perform(get("/tracking"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alice Beispiel")))
                .andExpect(content().string(containsString("alice@example.invalid")))
                .andExpect(content().string(containsString("bericht.docx")))
                .andExpect(content().string(containsString("Quartalsbericht Q3")))
                // Ausgeloestes Tracking wird als "Ja" angezeigt.
                .andExpect(content().string(containsString("Ja")))
                .andReturn();

        List<TrackingDashboardService.Row> rows = rowsOf(result);
        assertThat(rows).hasSize(1);
        TrackingDashboardService.Row row = rows.get(0);
        assertThat(row.recipientName()).isEqualTo("Alice Beispiel");
        assertThat(row.email()).isEqualTo("alice@example.invalid");
        assertThat(row.attachmentFilename()).isEqualTo("bericht.docx");
        assertThat(row.batchSubject()).isEqualTo("Quartalsbericht Q3");
        assertThat(row.triggered()).isTrue();
        assertThat(row.clickCount()).isEqualTo(2L);
        assertThat(row.firstClick()).isNotNull();
        assertThat(row.lastClick()).isNotNull();
        assertThat(row.firstClick()).isBeforeOrEqualTo(row.lastClick());
    }

    // #21 + #22: WEDER Klartext-Token NOCH SHA-256-Hash duerfen im Admin-HTML erscheinen.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void dashboardNeverRendersTokenOrHash() throws Exception {
        seedDelivery("Bob Beispiel", "bob@example.invalid", "Sicherheitshinweis",
                "hinweis.pdf", TOKEN_HASH, 1);

        mockMvc.perform(get("/tracking"))
                .andExpect(status().isOk())
                // Die Zeile wird tatsaechlich gerendert (Beleg: die Assertion ist nicht vakuum-wahr) ...
                .andExpect(content().string(containsString("bob@example.invalid")))
                // ... aber WEDER der Klartext-Token NOCH sein SHA-256-Hash erscheinen im HTML.
                .andExpect(content().string(not(containsString(RAW_TOKEN))))
                .andExpect(content().string(not(containsString(TOKEN_HASH))));
    }

    // Filter: Suche nach Name grenzt die Zeilen ein; Zusammenfassung bleibt global.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void filterByNameNarrowsRowsButKeepsSummaryGlobal() throws Exception {
        seedDelivery("Alice Beispiel", "alice@example.invalid", "Batch A", "a.docx",
                TrackingTokens.hash("t-alice"), 2);
        seedDelivery("Bob Beispiel", "bob@example.invalid", "Batch B", "b.docx",
                TrackingTokens.hash("t-bob"), 0);

        MvcResult result = mockMvc.perform(get("/tracking").param("query", "Alice"))
                .andExpect(status().isOk())
                .andReturn();

        List<TrackingDashboardService.Row> rows = rowsOf(result);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).recipientName()).isEqualTo("Alice Beispiel");

        // Zusammenfassung bleibt global (zaehlt beide tracking-faehigen Zustellungen).
        TrackingDashboardService.Summary summary = summaryOf(result);
        assertThat(summary.totalTrackedDeliveries()).isEqualTo(2L);
        assertThat(summary.respondingRecipients()).isEqualTo(1L);
        assertThat(summary.totalClicks()).isEqualTo(2L);
    }

    // Filter: Suche nach E-Mail grenzt die Zeilen ein.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void filterByEmailNarrowsRows() throws Exception {
        seedDelivery("Alice Beispiel", "alice@example.invalid", "Batch A", "a.docx",
                TrackingTokens.hash("t-alice"), 1);
        seedDelivery("Bob Beispiel", "bob@example.invalid", "Batch B", "b.docx",
                TrackingTokens.hash("t-bob"), 0);

        MvcResult result = mockMvc.perform(get("/tracking").param("query", "bob@example"))
                .andExpect(status().isOk())
                .andReturn();

        List<TrackingDashboardService.Row> rows = rowsOf(result);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).email()).isEqualTo("bob@example.invalid");
    }

    // Filter: "nur ausgeloeste" blendet Zustellungen ohne Klick aus.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void onlyTriggeredFilterHidesNonClicked() throws Exception {
        seedDelivery("Alice Beispiel", "alice@example.invalid", "Batch A", "a.docx",
                TrackingTokens.hash("t-alice"), 1);
        seedDelivery("Bob Beispiel", "bob@example.invalid", "Batch B", "b.docx",
                TrackingTokens.hash("t-bob"), 0);

        MvcResult result = mockMvc.perform(get("/tracking").param("onlyTriggered", "true"))
                .andExpect(status().isOk())
                .andReturn();

        List<TrackingDashboardService.Row> rows = rowsOf(result);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).triggered()).isTrue();
        assertThat(rows.get(0).recipientName()).isEqualTo("Alice Beispiel");
    }

    // Filter: nach Versandvorgang (batchId) grenzt auf genau einen Batch ein.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void filterByBatchIdNarrowsRows() throws Exception {
        MailDelivery alice = seedDelivery("Alice Beispiel", "alice@example.invalid", "Batch A", "a.docx",
                TrackingTokens.hash("t-alice"), 1);
        seedDelivery("Bob Beispiel", "bob@example.invalid", "Batch B", "b.docx",
                TrackingTokens.hash("t-bob"), 1);
        Long batchA = alice.getBatch().getId();

        MvcResult result = mockMvc.perform(get("/tracking").param("batchId", batchA.toString()))
                .andExpect(status().isOk())
                .andReturn();

        List<TrackingDashboardService.Row> rows = rowsOf(result);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).batchId()).isEqualTo(batchA);
        assertThat(rows.get(0).recipientName()).isEqualTo("Alice Beispiel");
    }

    // Nav: das Admin-Dashboard verlinkt auf /tracking (Navigationseintrag vorhanden).
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void navigationContainsTrackingLink() throws Exception {
        mockMvc.perform(get("/tracking"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/tracking\"")));
    }
}
