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
import de.internal.awareness.tracking.TrackingBatchStatsService;
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
 * Full-Stack-MVC-Tests der Batch-Auswertung ({@link TrackingBatchStatsController}) ueber MockMvc gegen die
 * isolierte SQLite-DB (Spec #13 "Batch-Auswertung korrekt" + Zugriffsschutz).
 *
 * <p>Geprueft werden: Zugriffsschutz (anonym -&gt; Login, Admin -&gt; 200 mit View {@code tracking/batches}),
 * korrekte per-Batch-Aggregation (Empfaengerzahl ueber ALLE Zustellungen, {@code SENT}/{@code FAILED}/
 * {@code NOT_SENT}, Responder/Aktionen aus getrackten Zustellungen, erster/letzter Event, Aktionsquote),
 * Sortierung (neueste zuerst), der Null-/Nullwert-Fall (Batch ohne Ereignisse zeigt 0/0 und Geviertstriche),
 * die Datenschutz-Garantie (WEDER Klartext-Token NOCH SHA-256-Hash im HTML, nicht vakuum-wahr) sowie der
 * Verlauf-Link auf {@code /mail/history/{id}}.</p>
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
class TrackingBatchStatsControllerTest {

    /** Geviertstrich, den {@code TrackingTimeFormat}/Template fuer fehlende Zeitpunkte bzw. Datei ausgeben. */
    private static final String EM_DASH = "—";

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

    /**
     * Legt einen Versandvorgang an. Das Feld {@code recipientCount} von {@link MailBatch} wird bewusst mit einem
     * FALSCHEN Wert belegt, um zu beweisen, dass die Auswertung die tatsaechliche Zahl der Zustellungen zaehlt
     * (und nicht dieses denormalisierte Feld).
     */
    private MailBatch newBatch(String subject, String filename, int bogusRecipientCountField) {
        return mailBatchRepository.save(new MailBatch(
                subject, "Text", "training@example.invalid", "IT Security", null, filename,
                bogusRecipientCountField));
    }

    /** Legt eine Zustellung mit gegebenem Status und optionalem Tracking-Hash an. */
    private MailDelivery newDelivery(MailBatch batch, String email, String name, String tokenHash,
                                     DeliveryStatus status) {
        Contact contact = contactRepository.save(new Contact(email, name));
        MailDelivery delivery = new MailDelivery(batch, contact, tokenHash);
        switch (status) {
            case SENT -> delivery.recordSent(Instant.parse("2026-09-01T10:15:30Z"));
            case FAILED -> delivery.recordFailure("SEND");
            case NOT_SENT -> { /* Ausgangszustand */ }
        }
        return mailDeliveryRepository.save(delivery);
    }

    /** Registriert {@code count} LINK_CLICK-Ereignisse ab {@code base} im Minutenabstand. */
    private void addEvents(MailDelivery delivery, Instant base, int count) {
        for (int i = 0; i < count; i++) {
            mailTrackingEventRepository.save(
                    new MailTrackingEvent(delivery, TrackingEventType.LINK_CLICK, base.plusSeconds(60L * i)));
        }
    }

    @SuppressWarnings("unchecked")
    private List<TrackingBatchStatsService.BatchStat> batchStatsOf(MvcResult result) {
        return (List<TrackingBatchStatsService.BatchStat>) result.getModelAndView().getModel().get("batches");
    }

    // --- Zugriffsschutz -----------------------------------------------------------------------------------

    // Ohne Login -> kein 200, Redirect zur Loginseite (globale Regel anyRequest().authenticated()).
    @Test
    void anonymousAccessRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/tracking/batches"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // Als Admin -> 200 und View "tracking/batches".
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void adminSeesBatchStatsPage() throws Exception {
        mockMvc.perform(get("/tracking/batches"))
                .andExpect(status().isOk())
                .andExpect(view().name("tracking/batches"))
                .andExpect(content().string(containsString("Batch-Auswertung")));
    }

    // Leerer Zustand: keine Batches -> Hinweistext, kein Fehler.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void emptyStateWhenNoBatches() throws Exception {
        MvcResult result = mockMvc.perform(get("/tracking/batches"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Keine Versandvorgaenge vorhanden.")))
                .andReturn();
        assertThat(batchStatsOf(result)).isEmpty();
    }

    // --- Kernauswertung (Spec #13) ------------------------------------------------------------------------

    // Zwei Batches mit gemischten Status und getrackten Zustellungen mit/ohne Events: alle Kennzahlen korrekt,
    // Reihenfolge neueste zuerst, Werte im HTML gerendert.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void batchStatsAreComputedAndRenderedCorrectly() throws Exception {
        // Batch 1 (aelter): 4 Zustellungen (2 SENT, 1 FAILED, 1 NOT_SENT); 2 getrackte Empfaenger mit Events.
        MailBatch alpha = newBatch("Alpha Bericht", "alpha.docx", 99);
        MailDelivery a = newDelivery(alpha, "a@example.invalid", "Alice", TrackingTokens.hash("h-a"), DeliveryStatus.SENT);
        MailDelivery b = newDelivery(alpha, "b@example.invalid", "Bob", TrackingTokens.hash("h-b"), DeliveryStatus.SENT);
        newDelivery(alpha, "c@example.invalid", "Carol", TrackingTokens.hash("h-c"), DeliveryStatus.FAILED);
        newDelivery(alpha, "d@example.invalid", "Dave", null, DeliveryStatus.NOT_SENT);
        addEvents(a, Instant.parse("2026-09-10T08:00:00Z"), 2); // 08:00 und 08:01
        addEvents(b, Instant.parse("2026-09-10T09:00:00Z"), 1); // 09:00 -> spaetestes Event des Batches

        // Batch 2 (neuer, zuletzt gespeichert): 1 getrackte Zustellung OHNE Events, kein Anhang.
        MailBatch beta = newBatch("Beta Hinweis", null, 42);
        newDelivery(beta, "e@example.invalid", "Eve", TrackingTokens.hash("h-e"), DeliveryStatus.SENT);

        MvcResult result = mockMvc.perform(get("/tracking/batches"))
                .andExpect(status().isOk())
                .andExpect(view().name("tracking/batches"))
                // Betreffe und Datei erscheinen im HTML (Beleg: die Zeilen werden tatsaechlich gerendert).
                .andExpect(content().string(containsString("Alpha Bericht")))
                .andExpect(content().string(containsString("Beta Hinweis")))
                .andExpect(content().string(containsString("alpha.docx")))
                .andReturn();

        List<TrackingBatchStatsService.BatchStat> stats = batchStatsOf(result);
        assertThat(stats).hasSize(2);

        // Reihenfolge: neueste zuerst -> Beta (zuletzt gespeichert) vor Alpha.
        TrackingBatchStatsService.BatchStat first = stats.get(0);
        TrackingBatchStatsService.BatchStat second = stats.get(1);
        assertThat(first.subject()).isEqualTo("Beta Hinweis");
        assertThat(second.subject()).isEqualTo("Alpha Bericht");
        assertThat(first.batchId()).isEqualTo(beta.getId());
        assertThat(second.batchId()).isEqualTo(alpha.getId());

        // Batch Alpha: Empfaengerzahl zaehlt ALLE Zustellungen (4), NICHT das Feld recipientCount (99).
        assertThat(second.recipientCount()).isEqualTo(4L);
        assertThat(second.sentCount()).isEqualTo(2L);
        assertThat(second.failedCount()).isEqualTo(1L);
        assertThat(second.notSentCount()).isEqualTo(1L);
        // Responder + Aktionen stammen NUR aus getrackten Zustellungen mit Events (A und B).
        assertThat(second.respondingRecipients()).isEqualTo(2L);
        assertThat(second.totalActions()).isEqualTo(3L);
        assertThat(second.firstEvent()).isEqualTo(Instant.parse("2026-09-10T08:00:00Z"));
        assertThat(second.lastEvent()).isEqualTo(Instant.parse("2026-09-10T09:00:00Z"));
        assertThat(second.attachmentFilename()).isEqualTo("alpha.docx");
        // Aktionsquote = respondingRecipients / recipientCount = 2/4 = 0.5.
        assertThat(second.actionRate()).isCloseTo(0.5, within(1e-9));

        // Batch Beta: getrackte Zustellung ohne Events -> 0/0 und keine Ereigniszeitpunkte.
        assertThat(first.recipientCount()).isEqualTo(1L);
        assertThat(first.sentCount()).isEqualTo(1L);
        assertThat(first.failedCount()).isEqualTo(0L);
        assertThat(first.notSentCount()).isEqualTo(0L);
        assertThat(first.respondingRecipients()).isEqualTo(0L);
        assertThat(first.totalActions()).isEqualTo(0L);
        assertThat(first.firstEvent()).isNull();
        assertThat(first.lastEvent()).isNull();
        assertThat(first.attachmentFilename()).isNull();
        assertThat(first.actionRate()).isCloseTo(0.0, within(1e-9));
    }

    // Ein Batch ganz ohne Events zeigt 0/0 und Geviertstriche - ohne Exception (Null-Ereigniszeitpunkte).
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void batchWithZeroEventsShowsZerosAndEmDashesWithoutException() throws Exception {
        MailBatch batch = newBatch("Ohne Aktion", null, 5);
        newDelivery(batch, "solo@example.invalid", "Solo", TrackingTokens.hash("h-solo"), DeliveryStatus.SENT);

        MvcResult result = mockMvc.perform(get("/tracking/batches"))
                .andExpect(status().isOk())
                // Zeile wird gerendert (Betreff sichtbar) ...
                .andExpect(content().string(containsString("Ohne Aktion")))
                // ... und die Geviertstriche fuer fehlende Datei/Ereigniszeitpunkte erscheinen.
                .andExpect(content().string(containsString(EM_DASH)))
                .andReturn();

        List<TrackingBatchStatsService.BatchStat> stats = batchStatsOf(result);
        assertThat(stats).hasSize(1);
        TrackingBatchStatsService.BatchStat s = stats.get(0);
        assertThat(s.respondingRecipients()).isEqualTo(0L);
        assertThat(s.totalActions()).isEqualTo(0L);
        assertThat(s.firstEvent()).isNull();
        assertThat(s.lastEvent()).isNull();
        assertThat(s.actionRate()).isCloseTo(0.0, within(1e-9));
    }

    // --- Datenschutz --------------------------------------------------------------------------------------

    // WEDER Klartext-Token NOCH SHA-256-Hash duerfen im Admin-HTML erscheinen (nicht vakuum-wahr: Zeile da).
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void batchStatsNeverRenderTokenOrHash() throws Exception {
        MailBatch batch = newBatch("Sicherheitshinweis", "hinweis.pdf", 1);
        MailDelivery delivery = newDelivery(batch, "victim@example.invalid", "Victim", TOKEN_HASH, DeliveryStatus.SENT);
        addEvents(delivery, Instant.parse("2026-09-11T07:30:00Z"), 1);

        mockMvc.perform(get("/tracking/batches"))
                .andExpect(status().isOk())
                // Die Zeile wird tatsaechlich gerendert (Beleg: die Assertion ist nicht vakuum-wahr) ...
                .andExpect(content().string(containsString("Sicherheitshinweis")))
                // ... aber WEDER der Klartext-Token NOCH sein SHA-256-Hash erscheinen im HTML.
                .andExpect(content().string(not(containsString(RAW_TOKEN))))
                .andExpect(content().string(not(containsString(TOKEN_HASH))));
    }

    // --- Verlauf-Link -------------------------------------------------------------------------------------

    // Jede Zeile verlinkt auf die Batch-Detailseite /mail/history/{id}.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void batchRowLinksToHistoryDetail() throws Exception {
        MailBatch batch = newBatch("Mit Verlauf", "x.docx", 1);
        newDelivery(batch, "link@example.invalid", "Link", TrackingTokens.hash("h-link"), DeliveryStatus.SENT);

        mockMvc.perform(get("/tracking/batches"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/mail/history/" + batch.getId() + "\"")));
    }
}
