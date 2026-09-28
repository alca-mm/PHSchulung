package de.internal.awareness.web;

import de.internal.awareness.contact.Contact;
import de.internal.awareness.contact.ContactRepository;
import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailBatchRepository;
import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.mail.MailDeliveryRepository;
import de.internal.awareness.recipient.DeliveryStatus;
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
import static org.assertj.core.api.Assertions.within;
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
 * (Empfaenger, Datei, Versandvorgang, Aktionszahlen, "Ja"-Badge), die globalen KPIs, die Filter (Name/E-Mail,
 * Versandvorgang, Dateiname, Versandstatus, Reaktion, Zeitraum) samt global bleibender Zusammenfassung, die
 * Sortierung (Anzahl Aktionen auf-/absteigend), die Formular-Wiederanzeige, die Links (Batch-Auswertung,
 * Detailseite je Zeile) sowie die zentrale Datenschutz-Garantie, dass WEDER der Klartext-Token NOCH sein
 * SHA-256-Hash im Admin-HTML erscheinen.
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

    private static final Instant DEFAULT_SENT_AT = Instant.parse("2026-09-01T10:15:30Z");

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

    /**
     * Kern-Seeder: legt eine tracking-faehige Zustellung (mit Hash) samt eigenem Kontakt/Batch an, setzt den
     * gewuenschten Versandstatus und optional {@code clicks} Klickereignisse.
     */
    private MailDelivery persist(String name, String email, String subject, String filename, String tokenHash,
                                 int clicks, DeliveryStatus status, Instant sentAt) {
        Contact contact = contactRepository.save(new Contact(email, name));
        MailBatch batch = mailBatchRepository.save(new MailBatch(
                subject, "Text", "training@example.invalid", "IT Security", null, filename, 1));
        MailDelivery delivery = new MailDelivery(batch, contact, tokenHash);
        if (status == DeliveryStatus.SENT) {
            delivery.recordSent(sentAt);
        } else if (status == DeliveryStatus.FAILED) {
            delivery.recordFailure("SEND");
        }
        delivery = mailDeliveryRepository.save(delivery);
        Instant base = Instant.parse("2026-09-02T08:00:00Z");
        for (int i = 0; i < clicks; i++) {
            mailTrackingEventRepository.save(
                    new MailTrackingEvent(delivery, TrackingEventType.LINK_CLICK, base.plusSeconds(60L * i)));
        }
        return delivery;
    }

    /** Bequemer Seeder fuer eine erfolgreich versendete (SENT) getrackte Zustellung mit festem Sendezeitpunkt. */
    private MailDelivery seedDelivery(String name, String email, String subject, String filename,
                                      String tokenHash, int clicks) {
        return persist(name, email, subject, filename, tokenHash, clicks, DeliveryStatus.SENT, DEFAULT_SENT_AT);
    }

    @SuppressWarnings("unchecked")
    private List<TrackingDashboardService.Row> rowsOf(MvcResult result) {
        return (List<TrackingDashboardService.Row>) result.getModelAndView().getModel().get("rows");
    }

    private TrackingDashboardService.Summary summaryOf(MvcResult result) {
        return (TrackingDashboardService.Summary) result.getModelAndView().getModel().get("summary");
    }

    // #1: ohne Login -> kein 200, Redirect zur Loginseite.
    @Test
    void anonymousAccessRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/tracking"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // #2: als Admin -> 200 und View "tracking/dashboard".
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void adminSeesDashboard() throws Exception {
        mockMvc.perform(get("/tracking"))
                .andExpect(status().isOk())
                .andExpect(view().name("tracking/dashboard"))
                .andExpect(content().string(containsString("Tracking")));
    }

    // Seed-Daten (Name/E-Mail/Datei/Batch/Aktionszahl/erster+letzter Klick) erscheinen im HTML; "Ja"-Badge.
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

    // #3/#4: WEDER Klartext-Token NOCH SHA-256-Hash duerfen im Admin-HTML erscheinen (nicht vakuum-wahr).
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

    // #5-9: globale KPIs werden gerendert und stimmen (totalRecipients, respondingRecipients,
    // nonRespondingRecipients, totalActions, actionRate als Anteil 0..1).
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void dashboardRendersGlobalKpis() throws Exception {
        seedDelivery("Alice Beispiel", "alice@example.invalid", "Batch A", "a.docx",
                TrackingTokens.hash("t-alice"), 3);
        seedDelivery("Bob Beispiel", "bob@example.invalid", "Batch B", "b.docx",
                TrackingTokens.hash("t-bob"), 0);
        seedDelivery("Carol Beispiel", "carol@example.invalid", "Batch C", "c.docx",
                TrackingTokens.hash("t-carol"), 2);

        MvcResult result = mockMvc.perform(get("/tracking"))
                .andExpect(status().isOk())
                // KPI-Kacheln (Beschriftungen) werden gerendert.
                .andExpect(content().string(containsString("Empfaenger insgesamt")))
                .andExpect(content().string(containsString("Empfaenger mit Aktion")))
                .andExpect(content().string(containsString("Empfaenger ohne Aktion")))
                .andExpect(content().string(containsString("Aktionen insgesamt")))
                .andExpect(content().string(containsString("Aktionsquote")))
                .andReturn();

        TrackingDashboardService.Summary summary = summaryOf(result);
        assertThat(summary.totalRecipients()).isEqualTo(3L);
        assertThat(summary.respondingRecipients()).isEqualTo(2L);
        assertThat(summary.nonRespondingRecipients()).isEqualTo(1L);
        assertThat(summary.totalActions()).isEqualTo(5L);
        assertThat(summary.sentTrackedDeliveries()).isEqualTo(3L);
        // actionRate ist ein Anteil (0..1) und entspricht respondingRecipients/totalRecipients.
        assertThat(summary.actionRate()).isGreaterThan(0.0).isLessThan(1.0);
        assertThat(summary.actionRate()).isCloseTo(2.0 / 3.0, within(1e-6));
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
        assertThat(summary.totalRecipients()).isEqualTo(2L);
        assertThat(summary.respondingRecipients()).isEqualTo(1L);
        assertThat(summary.totalActions()).isEqualTo(2L);
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

    // Filter: nach Dateiname (Teilstring, case-insensitiv) grenzt die Zeilen ein.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void filterByFileNameNarrowsRows() throws Exception {
        seedDelivery("Alice Beispiel", "alice@example.invalid", "Batch A", "rechnung-september.docx",
                TrackingTokens.hash("t-alice"), 1);
        seedDelivery("Bob Beispiel", "bob@example.invalid", "Batch B", "hinweis.pdf",
                TrackingTokens.hash("t-bob"), 0);

        MvcResult result = mockMvc.perform(get("/tracking").param("fileName", "rechnung"))
                .andExpect(status().isOk())
                .andReturn();

        List<TrackingDashboardService.Row> rows = rowsOf(result);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).attachmentFilename()).isEqualTo("rechnung-september.docx");

        // Zusammenfassung bleibt global.
        assertThat(summaryOf(result).totalRecipients()).isEqualTo(2L);
    }

    // Filter: nach Versandstatus grenzt die Zeilen ein; Zusammenfassung bleibt global.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void filterByStatusNarrowsRows() throws Exception {
        seedDelivery("Alice Beispiel", "alice@example.invalid", "Batch A", "a.docx",
                TrackingTokens.hash("t-alice"), 1);
        persist("Dave Beispiel", "dave@example.invalid", "Batch D", "d.docx",
                TrackingTokens.hash("t-dave"), 0, DeliveryStatus.FAILED, null);

        MvcResult result = mockMvc.perform(get("/tracking").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andReturn();

        List<TrackingDashboardService.Row> rows = rowsOf(result);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(rows.get(0).recipientName()).isEqualTo("Dave Beispiel");

        TrackingDashboardService.Summary summary = summaryOf(result);
        assertThat(summary.totalRecipients()).isEqualTo(2L);
        assertThat(summary.sentTrackedDeliveries()).isEqualTo(1L);
    }

    // Filter: reacted=REACTED zeigt nur Zustellungen mit mindestens einer Aktion.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void reactedFilterShowsOnlyResponders() throws Exception {
        seedDelivery("Alice Beispiel", "alice@example.invalid", "Batch A", "a.docx",
                TrackingTokens.hash("t-alice"), 1);
        seedDelivery("Bob Beispiel", "bob@example.invalid", "Batch B", "b.docx",
                TrackingTokens.hash("t-bob"), 0);

        MvcResult result = mockMvc.perform(get("/tracking").param("reacted", "REACTED"))
                .andExpect(status().isOk())
                .andReturn();

        List<TrackingDashboardService.Row> rows = rowsOf(result);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).triggered()).isTrue();
        assertThat(rows.get(0).recipientName()).isEqualTo("Alice Beispiel");
    }

    // Filter: reacted=NOT_REACTED zeigt nur Zustellungen ohne Aktion.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void notReactedFilterShowsOnlyNonResponders() throws Exception {
        seedDelivery("Alice Beispiel", "alice@example.invalid", "Batch A", "a.docx",
                TrackingTokens.hash("t-alice"), 2);
        seedDelivery("Bob Beispiel", "bob@example.invalid", "Batch B", "b.docx",
                TrackingTokens.hash("t-bob"), 0);

        MvcResult result = mockMvc.perform(get("/tracking").param("reacted", "NOT_REACTED"))
                .andExpect(status().isOk())
                .andReturn();

        List<TrackingDashboardService.Row> rows = rowsOf(result);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).triggered()).isFalse();
        assertThat(rows.get(0).recipientName()).isEqualTo("Bob Beispiel");
    }

    // Filter: Zeitraum (from/to als Datum, Europe/Berlin, to inklusiv) grenzt auf Zeilen im Zeitraum ein.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void dateRangeFilterNarrowsRows() throws Exception {
        // "inside": Versand am 2026-09-10 (Berlin), "outside": Versand am 2026-08-01.
        persist("Inside Beispiel", "inside@example.invalid", "Batch In", "in.docx",
                TrackingTokens.hash("t-in"), 1, DeliveryStatus.SENT, Instant.parse("2026-09-10T12:00:00Z"));
        persist("Outside Beispiel", "outside@example.invalid", "Batch Out", "out.docx",
                TrackingTokens.hash("t-out"), 1, DeliveryStatus.SENT, Instant.parse("2026-08-01T12:00:00Z"));

        MvcResult result = mockMvc.perform(get("/tracking")
                        .param("from", "2026-09-05")
                        .param("to", "2026-09-15"))
                .andExpect(status().isOk())
                .andReturn();

        List<TrackingDashboardService.Row> rows = rowsOf(result);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).recipientName()).isEqualTo("Inside Beispiel");

        // Zusammenfassung bleibt global (beide Zustellungen).
        assertThat(summaryOf(result).totalRecipients()).isEqualTo(2L);
    }

    // Sortierung: sort=ACTIONS asc vs. desc kehrt die Reihenfolge der beiden Zeilen um.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void sortByActionsAscVsDescChangesOrder() throws Exception {
        seedDelivery("More Klicks", "more@example.invalid", "Batch More", "more.docx",
                TrackingTokens.hash("t-more"), 3);
        seedDelivery("Less Klicks", "less@example.invalid", "Batch Less", "less.docx",
                TrackingTokens.hash("t-less"), 1);

        MvcResult asc = mockMvc.perform(get("/tracking")
                        .param("sort", "ACTIONS").param("dir", "ASC"))
                .andExpect(status().isOk())
                .andReturn();
        List<TrackingDashboardService.Row> ascRows = rowsOf(asc);
        assertThat(ascRows).hasSize(2);
        assertThat(ascRows.get(0).clickCount()).isEqualTo(1L);
        assertThat(ascRows.get(1).clickCount()).isEqualTo(3L);

        MvcResult desc = mockMvc.perform(get("/tracking")
                        .param("sort", "ACTIONS").param("dir", "DESC"))
                .andExpect(status().isOk())
                .andReturn();
        List<TrackingDashboardService.Row> descRows = rowsOf(desc);
        assertThat(descRows).hasSize(2);
        assertThat(descRows.get(0).clickCount()).isEqualTo(3L);
        assertThat(descRows.get(1).clickCount()).isEqualTo(1L);
    }

    // Formular-Wiederanzeige: nach dem Filtern behalten die Eingabefelder ihre Werte.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void formRepopulatesSubmittedFilters() throws Exception {
        seedDelivery("Alice Beispiel", "alice@example.invalid", "Batch A", "bericht.docx",
                TrackingTokens.hash("t-alice"), 1);

        mockMvc.perform(get("/tracking")
                        .param("query", "Alice")
                        .param("fileName", "bericht.docx")
                        .param("status", "SENT")
                        .param("reacted", "REACTED")
                        .param("from", "2026-09-05")
                        .param("to", "2026-09-15")
                        .param("sort", "ACTIONS")
                        .param("dir", "ASC"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"Alice\"")))
                .andExpect(content().string(containsString("value=\"bericht.docx\"")))
                .andExpect(content().string(containsString("value=\"2026-09-05\"")))
                .andExpect(content().string(containsString("value=\"2026-09-15\"")))
                // Sortierung wird als Hidden-Feld bewahrt.
                .andExpect(content().string(containsString("value=\"ACTIONS\"")))
                .andExpect(content().string(containsString("value=\"ASC\"")))
                // Ausgewaehlte Select-Optionen (Status/Reaktion) bleiben markiert.
                .andExpect(content().string(containsString("value=\"SENT\" selected")))
                .andExpect(content().string(containsString("value=\"REACTED\" selected")));
    }

    // Links: prominenter Batch-Auswertungs-Link und je Zeile ein Detail-Link.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void dashboardContainsBatchAndDetailLinks() throws Exception {
        MailDelivery alice = seedDelivery("Alice Beispiel", "alice@example.invalid", "Batch A", "a.docx",
                TrackingTokens.hash("t-alice"), 1);

        mockMvc.perform(get("/tracking"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/tracking/batches")))
                .andExpect(content().string(containsString("/tracking/delivery/" + alice.getId())));
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
