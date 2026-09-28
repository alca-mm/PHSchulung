package de.internal.awareness.web.api;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import de.internal.awareness.contact.Contact;
import de.internal.awareness.contact.ContactRepository;
import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailBatchRepository;
import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.mail.MailDeliveryRepository;
import de.internal.awareness.recipient.DeliveryStatus;
import de.internal.awareness.tracking.MailTrackingEvent;
import de.internal.awareness.tracking.MailTrackingEventRepository;
import de.internal.awareness.tracking.TrackingEventType;
import de.internal.awareness.tracking.TrackingTokens;
import de.internal.awareness.web.api.dto.BatchStatDto;
import de.internal.awareness.web.api.dto.DashboardResponse;
import de.internal.awareness.web.api.dto.DeliveryDetailResponse;
import de.internal.awareness.web.api.dto.TimelineEntryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-Stack-MVC-Tests der Tracking-JSON-API ({@link TrackingApiController}) ueber MockMvc gegen die isolierte
 * SQLite-DB. Die Testdaten werden - wie in {@code TrackingDashboardServiceTest} / {@code TrackingDashboard
 * ControllerTest} - ueber die bestehenden Repositories/Entities aufgebaut.
 *
 * <p>Abgedeckt: JSON-Grundgestalt des Dashboards (summary/rows/batches/filter), korrekte KPI-Werte, die Filter
 * (Name/E-Mail, Batch, Dateiname, Status, Reaktion, Zeitraum) samt global bleibender Zusammenfassung, die
 * Sortierung (auf-/absteigend), die Detailantwort (recipient/delivery/tracking/timeline) inkl. chronologischer
 * Timeline, die Batch-Auswertung, die kontrollierte 404-JSON-Antwort fuer eine unbekannte Id (ohne Stacktrace/
 * HTML) sowie - sicherheitskritisch - dass in KEINER Antwort ein Token-Hash oder Klartext-Token auftaucht und
 * dass Zeitpunkte als ISO-8601-Zeichenketten serialisiert werden.</p>
 *
 * <p>Verwendet {@link WithMockUser}, damit der Test nicht von einem etwaigen Bearer-Filter abhaengt; die
 * Autorisierung greift weiterhin ueber die vorhandene Security-Kette. Die {@code app.files.generated-dir}-Property
 * wird auf ein {@link TempDir} umgebogen, damit das echte {@code ./data}-Verzeichnis nie beruehrt wird.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.test.context.TestExecutionListeners(
        listeners = org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener.class,
        mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class TrackingApiControllerTest {

    // Bekannter Klartext-Token, der NIEMALS ausgegeben werden darf (nur der Hash wird gespeichert).
    private static final String RAW_TOKEN = "RAWtokenDoNotRender_0123456789abcdefghijABC";
    // Der zugehoerige SHA-256-Hex-Hash, der ebenfalls nie in einer Antwort erscheinen darf.
    private static final String TOKEN_HASH = TrackingTokens.hash(RAW_TOKEN);

    // Vollstaendige ISO-8601-UTC-Form (mit 'Z'), optional mit Sekundenbruchteil.
    private static final String ISO_INSTANT_REGEX = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

    @TempDir
    static Path generatedDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.files.generated-dir", () -> generatedDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private MailBatchRepository batchRepository;

    @Autowired
    private MailDeliveryRepository deliveryRepository;

    @Autowired
    private MailTrackingEventRepository eventRepository;

    // Fixe Zeitachse (auf Millisekunden gekuerzt, damit SQLite-Rueckgaben exakt vergleichbar sind).
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private MailBatch batch1;   // "Rechnung September" - Anhang "rechnung.docx", 3 Zustellungen
    private MailBatch batch2;   // "Passwort zuruecksetzen" - kein Anhang, 1 getrackte Zustellung
    private MailBatch batch3;   // "Nur ungetrackt" - 1 UNGETRACKTE Zustellung

    private Long d1Id;  // Alice, batch1, SENT (now-50), 2 Klicks, TOKEN_HASH
    private Long d2Id;  // Bob,   batch1, SENT (now-30), 1 Klick
    private Long d3Id;  // Carol, batch1, FAILED (sentAt null), 0 Klicks
    private Long d4Id;  // Dave,  batch2, SENT (now-10), attemptCount 1, 3 Klicks (now-10, now-8, now-5)

    @BeforeEach
    void setUp() {
        batch1 = batchRepository.saveAndFlush(new MailBatch(
                "Rechnung September", "Hallo", "training@example.invalid", "IT Security", null, "rechnung.docx", 3));
        batch2 = batchRepository.saveAndFlush(new MailBatch(
                "Passwort zuruecksetzen", "Hallo", "training@example.invalid", "IT Security", null, null, 1));
        batch3 = batchRepository.saveAndFlush(new MailBatch(
                "Nur ungetrackt", "Hallo", "training@example.invalid", "IT Security", null, null, 1));

        MailDelivery d1 = newDelivery(batch1, "Alice Anderson", "alice@example.invalid", TOKEN_HASH);
        d1.recordSent(now.minus(50, ChronoUnit.MINUTES));
        MailDelivery d2 = newDelivery(batch1, "Bob Baker", "bob@example.invalid",
                TrackingTokens.generate().tokenHash());
        d2.recordSent(now.minus(30, ChronoUnit.MINUTES));
        MailDelivery d3 = newDelivery(batch1, "Carol Clark", "carol@example.invalid",
                TrackingTokens.generate().tokenHash());
        d3.recordFailure("SEND"); // FAILED, sentAt bleibt null, keine Klicks
        MailDelivery d4 = newDelivery(batch2, "Dave Dunn", "dave@example.invalid",
                TrackingTokens.generate().tokenHash());
        d4.recordAttempt(); // attemptCount -> 1
        d4.recordSent(now.minus(10, ChronoUnit.MINUTES));
        // UNGETRACKTE Zustellung (kein Trainingslink) - nur fuer die Batch-Auswertung relevant.
        MailDelivery d5 = newDelivery(batch3, "Eve Evans", "eve@example.invalid", null);
        d5.recordSent(now);

        deliveryRepository.saveAndFlush(d1);
        deliveryRepository.saveAndFlush(d2);
        deliveryRepository.saveAndFlush(d3);
        deliveryRepository.saveAndFlush(d4);
        deliveryRepository.saveAndFlush(d5);

        d1Id = d1.getId();
        d2Id = d2.getId();
        d3Id = d3.getId();
        d4Id = d4.getId();

        click(d1, now.minus(40, ChronoUnit.MINUTES));
        click(d1, now.minus(35, ChronoUnit.MINUTES));
        click(d2, now.minus(20, ChronoUnit.MINUTES));
        click(d4, now.minus(10, ChronoUnit.MINUTES));
        click(d4, now.minus(8, ChronoUnit.MINUTES));
        click(d4, now.minus(5, ChronoUnit.MINUTES));
        eventRepository.flush();
    }

    private MailDelivery newDelivery(MailBatch batch, String name, String email, String hash) {
        Contact contact = contactRepository.saveAndFlush(new Contact(email, name));
        return new MailDelivery(batch, contact, hash);
    }

    private void click(MailDelivery delivery, Instant when) {
        eventRepository.save(new MailTrackingEvent(delivery, TrackingEventType.LINK_CLICK, when));
    }

    private DashboardResponse dashboard(String... queryParams) throws Exception {
        var request = get("/api/tracking");
        for (int i = 0; i + 1 < queryParams.length; i += 2) {
            request = request.param(queryParams[i], queryParams[i + 1]);
        }
        String json = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, DashboardResponse.class);
    }

    // #13: JSON-Grundgestalt (summary + rows + batches + filter) inkl. Content-Type.
    @Test
    @WithMockUser(roles = "ADMIN")
    void dashboardJsonHasSummaryRowsBatchesFilter() throws Exception {
        mockMvc.perform(get("/api/tracking"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.summary").exists())
                .andExpect(jsonPath("$.summary.trackedDeliveries").value(4))
                .andExpect(jsonPath("$.rows", hasSize(4)))
                .andExpect(jsonPath("$.rows[0].deliveryId").exists())
                .andExpect(jsonPath("$.rows[0].contactEmail").exists())
                .andExpect(jsonPath("$.rows[0].deliveryStatus").exists())
                // batch3 (nur ungetrackt) erscheint NICHT im Dashboard-Dropdown -> genau 2 Optionen.
                .andExpect(jsonPath("$.batches", hasSize(2)))
                .andExpect(jsonPath("$.filter").exists())
                .andExpect(jsonPath("$.filter.reacted").value("ALL"))
                .andExpect(jsonPath("$.filter.sort").value("LAST_CLICK"))
                .andExpect(jsonPath("$.filter.dir").value("DESC"))
                .andExpect(jsonPath("$.filter.query").value(nullValue()))
                .andExpect(jsonPath("$.filter.from").value(nullValue()))
                .andExpect(jsonPath("$.filter.to").value(nullValue()));
    }

    // #14: KPI-Werte korrekt (global ueber getrackte Zustellungen, ungetrackte d5 ausgeschlossen).
    @Test
    @WithMockUser(roles = "ADMIN")
    void dashboardKpiValuesAreCorrect() throws Exception {
        DashboardResponse resp = dashboard();
        var s = resp.summary();
        assertThat(s.trackedDeliveries()).isEqualTo(4L);
        assertThat(s.sentDeliveries()).isEqualTo(3L);
        assertThat(s.reactedDeliveries()).isEqualTo(3L);
        assertThat(s.notReactedDeliveries()).isEqualTo(1L);
        assertThat(s.totalActions()).isEqualTo(6L);
        assertThat(s.reactionRate()).isEqualTo(0.75);
        assertThat(s.averageActions()).isEqualTo(2.0);
    }

    // #15: Freitextfilter (Name) grenzt Zeilen ein; Summary bleibt global.
    @Test
    @WithMockUser(roles = "ADMIN")
    void filterByQueryNameNarrowsRowsButSummaryStaysGlobal() throws Exception {
        DashboardResponse resp = dashboard("query", "anderson");
        assertThat(resp.rows()).extracting("deliveryId").containsExactly(d1Id);
        // Summary bleibt global.
        assertThat(resp.summary().trackedDeliveries()).isEqualTo(4L);
        assertThat(resp.summary().reactedDeliveries()).isEqualTo(3L);
    }

    // #15: Freitextfilter (E-Mail) grenzt Zeilen ein.
    @Test
    @WithMockUser(roles = "ADMIN")
    void filterByQueryEmailNarrowsRows() throws Exception {
        DashboardResponse resp = dashboard("query", "bob@example");
        assertThat(resp.rows()).extracting("deliveryId").containsExactly(d2Id);
    }

    // #15: batchId-Filter grenzt auf genau einen Batch ein; Summary bleibt global.
    @Test
    @WithMockUser(roles = "ADMIN")
    void filterByBatchIdNarrowsRows() throws Exception {
        DashboardResponse resp = dashboard("batchId", batch2.getId().toString());
        assertThat(resp.rows()).extracting("deliveryId").containsExactly(d4Id);
        assertThat(resp.summary().trackedDeliveries()).isEqualTo(4L);
    }

    // #15: Dateiname-Filter (Teilstring, case-insensitiv) inkl. Ausschluss der Zeile ohne Anhang.
    @Test
    @WithMockUser(roles = "ADMIN")
    void filterByFileNameNarrowsRows() throws Exception {
        DashboardResponse resp = dashboard("fileName", "RECHNUNG");
        assertThat(resp.rows()).extracting("deliveryId").containsExactlyInAnyOrder(d1Id, d2Id, d3Id);
        assertThat(resp.rows()).extracting("deliveryId").doesNotContain(d4Id);
    }

    // #15: Status-Filter (exakt) grenzt Zeilen ein.
    @Test
    @WithMockUser(roles = "ADMIN")
    void filterByStatusNarrowsRows() throws Exception {
        assertThat(dashboard("status", "sent").rows()).extracting("deliveryId")
                .containsExactlyInAnyOrder(d1Id, d2Id, d4Id);
        assertThat(dashboard("status", "FAILED").rows()).extracting("deliveryId")
                .containsExactly(d3Id);
    }

    // #15: Reaktionsfilter (REACTED / NOT_REACTED).
    @Test
    @WithMockUser(roles = "ADMIN")
    void filterByReactedNarrowsRows() throws Exception {
        assertThat(dashboard("reacted", "REACTED").rows()).extracting("deliveryId")
                .containsExactlyInAnyOrder(d1Id, d2Id, d4Id);
        assertThat(dashboard("reacted", "NOT_REACTED").rows()).extracting("deliveryId")
                .containsExactly(d3Id);
    }

    // #15: Zeitraumfilter (from/to als Datum, Europe/Berlin, inklusiv) grenzt auf Zeilen im Zeitraum ein.
    @Test
    @WithMockUser(roles = "ADMIN")
    void filterByDateRangeNarrowsRows() throws Exception {
        // Zwei zusaetzliche Zustellungen mit fixem Sendezeitpunkt: eine im Fenster, eine ausserhalb.
        MailBatch dateBatch = batchRepository.saveAndFlush(new MailBatch(
                "Datum-Batch", "Hallo", "training@example.invalid", "IT Security", null, "d.docx", 2));
        MailDelivery inside = newDelivery(dateBatch, "Inside Beispiel", "inside@example.invalid",
                TrackingTokens.generate().tokenHash());
        inside.recordSent(Instant.parse("2020-06-15T12:00:00Z"));
        MailDelivery outside = newDelivery(dateBatch, "Outside Beispiel", "outside@example.invalid",
                TrackingTokens.generate().tokenHash());
        outside.recordSent(Instant.parse("2019-01-01T12:00:00Z"));
        deliveryRepository.saveAndFlush(inside);
        deliveryRepository.saveAndFlush(outside);
        deliveryRepository.flush();

        DashboardResponse resp = dashboard("from", "2020-06-01", "to", "2020-06-30");
        assertThat(resp.rows()).extracting("contactName").containsExactly("Inside Beispiel");
        // Summary bleibt global (jetzt 6 getrackte Zustellungen).
        assertThat(resp.summary().trackedDeliveries()).isEqualTo(6L);
        // Filter-Echo enthaelt exakt die uebergebenen Datumszeichenketten.
        assertThat(resp.filter().from()).isEqualTo("2020-06-01");
        assertThat(resp.filter().to()).isEqualTo("2020-06-30");
    }

    // #16: Sortierung ACTIONS asc vs. desc kehrt die Reihenfolge um.
    @Test
    @WithMockUser(roles = "ADMIN")
    void sortByActionsAscVsDescChangesOrder() throws Exception {
        DashboardResponse asc = dashboard("sort", "ACTIONS", "dir", "ASC");
        assertThat(asc.rows()).extracting("clickCount").containsExactly(0L, 1L, 2L, 3L);

        DashboardResponse desc = dashboard("sort", "ACTIONS", "dir", "DESC");
        assertThat(desc.rows()).extracting("clickCount").containsExactly(3L, 2L, 1L, 0L);
    }

    // Robustheit: ungueltige Parameter fallen auf sichere Defaults zurueck (nie 500).
    @Test
    @WithMockUser(roles = "ADMIN")
    void invalidFilterParametersFallBackToDefaultsNo500() throws Exception {
        DashboardResponse resp = dashboard(
                "status", "BOGUS", "reacted", "BOGUS", "sort", "BOGUS", "dir", "BOGUS",
                "from", "not-a-date", "to", "31-12-2020");
        assertThat(resp.rows()).hasSize(4);
        assertThat(resp.filter().status()).isNull();
        assertThat(resp.filter().reacted()).isEqualTo("ALL");
        assertThat(resp.filter().sort()).isEqualTo("LAST_CLICK");
        assertThat(resp.filter().dir()).isEqualTo("DESC");
        assertThat(resp.filter().from()).isNull();
        assertThat(resp.filter().to()).isNull();
    }

    // #17: Detailantwort korrekt (recipient / delivery / tracking / timeline) inkl. ISO-8601-Zeitpunkt.
    @Test
    @WithMockUser(roles = "ADMIN")
    void deliveryDetailJsonIsCorrect() throws Exception {
        String json = mockMvc.perform(get("/api/tracking/deliveries/" + d4Id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.recipient.name").value("Dave Dunn"))
                .andExpect(jsonPath("$.recipient.email").value("dave@example.invalid"))
                .andExpect(jsonPath("$.delivery.deliveryId").value(d4Id.intValue()))
                .andExpect(jsonPath("$.delivery.batchId").value(batch2.getId().intValue()))
                .andExpect(jsonPath("$.delivery.subject").value("Passwort zuruecksetzen"))
                .andExpect(jsonPath("$.delivery.attachmentFilename").value(nullValue()))
                .andExpect(jsonPath("$.delivery.deliveryStatus").value("SENT"))
                .andExpect(jsonPath("$.delivery.attemptCount").value(1))
                .andExpect(jsonPath("$.delivery.sentAt").value(matchesPattern(ISO_INSTANT_REGEX)))
                .andExpect(jsonPath("$.tracking.reacted").value(true))
                .andExpect(jsonPath("$.tracking.clickCount").value(3))
                .andExpect(jsonPath("$.timeline", hasSize(3)))
                .andReturn().getResponse().getContentAsString();

        DeliveryDetailResponse resp = objectMapper.readValue(json, DeliveryDetailResponse.class);
        assertThat(resp.tracking().firstClick()).isEqualTo(now.minus(10, ChronoUnit.MINUTES));
        assertThat(resp.tracking().lastClick()).isEqualTo(now.minus(5, ChronoUnit.MINUTES));
        assertThat(resp.delivery().sentAt()).isEqualTo(now.minus(10, ChronoUnit.MINUTES));
    }

    // #18: Timeline chronologisch aufsteigend, alle Ereignisse LINK_CLICK.
    @Test
    @WithMockUser(roles = "ADMIN")
    void deliveryTimelineIsChronological() throws Exception {
        String json = mockMvc.perform(get("/api/tracking/deliveries/" + d4Id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        DeliveryDetailResponse resp = objectMapper.readValue(json, DeliveryDetailResponse.class);

        assertThat(resp.timeline()).extracting(TimelineEntryDto::type).containsOnly("LINK_CLICK");
        assertThat(resp.timeline()).extracting(TimelineEntryDto::occurredAt)
                .containsExactly(
                        now.minus(10, ChronoUnit.MINUTES),
                        now.minus(8, ChronoUnit.MINUTES),
                        now.minus(5, ChronoUnit.MINUTES));
        // Streng monoton aufsteigend.
        assertThat(resp.timeline().get(0).occurredAt()).isBefore(resp.timeline().get(1).occurredAt());
        assertThat(resp.timeline().get(1).occurredAt()).isBefore(resp.timeline().get(2).occurredAt());
    }

    // #19: Batch-Auswertung korrekt (alle Batches mit >=1 Zustellung, neueste zuerst).
    @Test
    @WithMockUser(roles = "ADMIN")
    void batchesJsonIsCorrect() throws Exception {
        String json = mockMvc.perform(get("/api/tracking/batches"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        List<BatchStatDto> stats = objectMapper.readValue(json, new TypeReference<List<BatchStatDto>>() {
        });

        // Alle drei Batches vorhanden, neueste zuerst (batch3, batch2, batch1).
        assertThat(stats).extracting(BatchStatDto::batchId)
                .containsExactly(batch3.getId(), batch2.getId(), batch1.getId());

        BatchStatDto b1 = byId(stats, batch1.getId());
        assertThat(b1.subject()).isEqualTo("Rechnung September");
        assertThat(b1.attachmentFilename()).isEqualTo("rechnung.docx");
        assertThat(b1.recipientCount()).isEqualTo(3L);
        assertThat(b1.sentCount()).isEqualTo(2L);
        assertThat(b1.failedCount()).isEqualTo(1L);
        assertThat(b1.notSentCount()).isEqualTo(0L);
        assertThat(b1.reactedRecipients()).isEqualTo(2L);
        assertThat(b1.totalActions()).isEqualTo(3L);
        assertThat(b1.reactionRate()).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(b1.firstEvent()).isNotNull();
        assertThat(b1.lastEvent()).isNotNull();
        assertThat(b1.createdAt()).isNotNull();

        BatchStatDto b2 = byId(stats, batch2.getId());
        assertThat(b2.recipientCount()).isEqualTo(1L);
        assertThat(b2.sentCount()).isEqualTo(1L);
        assertThat(b2.reactedRecipients()).isEqualTo(1L);
        assertThat(b2.totalActions()).isEqualTo(3L);

        BatchStatDto b3 = byId(stats, batch3.getId());
        assertThat(b3.recipientCount()).isEqualTo(1L);
        assertThat(b3.reactedRecipients()).isEqualTo(0L);
        assertThat(b3.totalActions()).isEqualTo(0L);
        assertThat(b3.firstEvent()).isNull();
        assertThat(b3.lastEvent()).isNull();
    }

    private static BatchStatDto byId(List<BatchStatDto> stats, Long id) {
        return stats.stream().filter(s -> s.batchId().equals(id)).findFirst().orElseThrow();
    }

    // #20: unbekannte Id -> 404 als JSON (ApiError), OHNE Stacktrace / "Exception" / HTML.
    @Test
    @WithMockUser(roles = "ADMIN")
    void unknownDeliveryIdReturns404Json() throws Exception {
        String body = mockMvc.perform(get("/api/tracking/deliveries/999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.message").exists())
                .andReturn().getResponse().getContentAsString();

        // Weder Stacktrace-Fragmente noch HTML der Fehlerseite.
        assertThat(body).doesNotContain("Exception");
        assertThat(body).doesNotContain("\tat ");
        assertThat(body).doesNotContain("<html");
        assertThat(body).doesNotContain("<!DOCTYPE");
    }

    // #21/#22: In KEINER Antwort tauchen Token-Hash oder Klartext-Token auf (Belege: nicht vakuum-wahr).
    @Test
    @WithMockUser(roles = "ADMIN")
    void noTokenHashOrRawTokenLeaksInAnyResponse() throws Exception {
        String dash = mockMvc.perform(get("/api/tracking"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alice@example.invalid")))
                .andExpect(content().string(not(containsString(TOKEN_HASH))))
                .andExpect(content().string(not(containsString(RAW_TOKEN))))
                .andReturn().getResponse().getContentAsString();
        assertThat(dash).doesNotContain(TOKEN_HASH).doesNotContain(RAW_TOKEN);

        String detail = mockMvc.perform(get("/api/tracking/deliveries/" + d1Id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alice@example.invalid")))
                .andExpect(content().string(not(containsString(TOKEN_HASH))))
                .andExpect(content().string(not(containsString(RAW_TOKEN))))
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain(TOKEN_HASH).doesNotContain(RAW_TOKEN);

        String batches = mockMvc.perform(get("/api/tracking/batches"))
                .andExpect(status().isOk())
                // Beleg, dass batch1 (mit der TOKEN_HASH-Zustellung) tatsaechlich enthalten ist.
                .andExpect(content().string(containsString("Rechnung September")))
                .andExpect(content().string(not(containsString(TOKEN_HASH))))
                .andExpect(content().string(not(containsString(RAW_TOKEN))))
                .andReturn().getResponse().getContentAsString();
        assertThat(batches).doesNotContain(TOKEN_HASH).doesNotContain(RAW_TOKEN);
    }

    // Zeitpunkte werden als ISO-8601-Zeichenketten serialisiert (Frontend formatiert Berlin-Anzeige selbst).
    @Test
    @WithMockUser(roles = "ADMIN")
    void instantsSerializeAsIso8601Strings() throws Exception {
        // Dashboard: der Sendezeitpunkt der ersten (nach LAST_CLICK DESC) Zeile (d4) ist ein ISO-Instant.
        mockMvc.perform(get("/api/tracking"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].sentAt").value(matchesPattern(ISO_INSTANT_REGEX)))
                .andExpect(jsonPath("$.rows[0].lastClick").value(matchesPattern(ISO_INSTANT_REGEX)));

        // Batch-Auswertung: createdAt / firstEvent sind ISO-Instants.
        mockMvc.perform(get("/api/tracking/batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].createdAt").value(matchesPattern(ISO_INSTANT_REGEX)));
    }
}
