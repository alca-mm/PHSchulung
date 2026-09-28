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
import de.internal.awareness.tracking.TrackingDeliveryService;
import de.internal.awareness.tracking.TrackingEventType;
import de.internal.awareness.tracking.TrackingTimeFormat;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Full-Stack-MVC-Tests der Zustell-Detailseite ({@link TrackingDeliveryController}) ueber MockMvc gegen die
 * isolierte SQLite-DB. Prueft Zugriffsschutz (anonym -&gt; Login), die Anzeige der Detaildaten (Empfaenger,
 * Versandvorgang, Datei, Status, Versuchszaehler), die chronologische Ereignis-Timeline samt korrekter
 * Kennzahlen (actionCount/erster/letzter Zeitpunkt, "Ja"), die Leerzustaende (getrackt ohne Ereignis -&gt;
 * "Nein"; ohne Anhang/ohne Sendezeit -&gt; Geviertstriche), die kontrollierte 404-Antwort bei unbekannter Id
 * sowie die zentrale Datenschutz-Garantie, dass WEDER Klartext-Token NOCH SHA-256-Hash NOCH User-Agent/IP im
 * Admin-HTML erscheinen.
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
class TrackingDeliveryControllerTest {

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

    @Autowired
    private TrackingTimeFormat trackingTimeFormat;

    /**
     * Legt eine Zustellung mit gewuenschtem Status/Anhang/Versuchszaehler an und optional Klickereignisse zu den
     * angegebenen Zeitpunkten (in beliebiger Reihenfolge). Liefert die gespeicherte Zustellung zurueck.
     */
    private MailDelivery seedDelivery(String name, String email, String subject, String filename,
                                      String tokenHash, DeliveryStatus status, Instant sentAt,
                                      int attempts, Instant... clickTimes) {
        Contact contact = contactRepository.save(new Contact(email, name));
        MailBatch batch = mailBatchRepository.save(new MailBatch(
                subject, "Text", "training@example.invalid", "IT Security", null, filename, 1));
        MailDelivery delivery = new MailDelivery(batch, contact, tokenHash);
        for (int i = 0; i < attempts; i++) {
            delivery.recordAttempt();
        }
        if (status == DeliveryStatus.SENT) {
            delivery.recordSent(sentAt);
        } else if (status == DeliveryStatus.FAILED) {
            delivery.recordFailure("SEND");
        }
        // NOT_SENT: Ausgangszustand (kein sentAt) bewusst belassen.
        delivery = mailDeliveryRepository.save(delivery);
        for (Instant t : clickTimes) {
            mailTrackingEventRepository.save(new MailTrackingEvent(delivery, TrackingEventType.LINK_CLICK, t));
        }
        return delivery;
    }

    private TrackingDeliveryService.DeliveryDetail detailOf(MvcResult result) {
        return (TrackingDeliveryService.DeliveryDetail) result.getModelAndView().getModel().get("detail");
    }

    // #23: ohne Login -> kein 200, Redirect zur Loginseite (globale anyRequest().authenticated()-Regel).
    @Test
    void anonymousAccessRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/tracking/delivery/{id}", 1L))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // #23 (Admin): 200, View "tracking/delivery-detail", zeigt Empfaenger/Batch/Datei/Status/Versuchszaehler.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void adminSeesDeliveryDetail() throws Exception {
        MailDelivery delivery = seedDelivery("Alice Beispiel", "alice@example.invalid", "Quartalsbericht Q3",
                "bericht.docx", TrackingTokens.hash("alice-token"), DeliveryStatus.SENT,
                Instant.parse("2026-09-01T10:15:30Z"), 3,
                Instant.parse("2026-09-02T08:00:00Z"));

        MvcResult result = mockMvc.perform(get("/tracking/delivery/{id}", delivery.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("tracking/delivery-detail"))
                .andExpect(content().string(containsString("Alice Beispiel")))
                .andExpect(content().string(containsString("alice@example.invalid")))
                .andExpect(content().string(containsString("Quartalsbericht Q3")))
                .andExpect(content().string(containsString("bericht.docx")))
                .andExpect(content().string(containsString("SENT")))
                .andExpect(content().string(containsString("Anzahl Versandversuche")))
                .andReturn();

        TrackingDeliveryService.DeliveryDetail detail = detailOf(result);
        assertThat(detail.deliveryId()).isEqualTo(delivery.getId());
        assertThat(detail.recipientName()).isEqualTo("Alice Beispiel");
        assertThat(detail.email()).isEqualTo("alice@example.invalid");
        assertThat(detail.batchSubject()).isEqualTo("Quartalsbericht Q3");
        assertThat(detail.attachmentFilename()).isEqualTo("bericht.docx");
        assertThat(detail.status()).isEqualTo(DeliveryStatus.SENT);
        assertThat(detail.attemptCount()).isEqualTo(3);
    }

    // #24: Timeline mit mehreren Ereignissen (verschiedene Zeiten, bewusst unsortiert eingefuegt) -> chronologisch
    // aufsteigend; actionCount/erster/letzter Zeitpunkt korrekt; triggered "Ja".
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void timelineShowsEventsChronologically() throws Exception {
        Instant t1 = Instant.parse("2026-09-02T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-02T09:30:00Z");
        Instant t3 = Instant.parse("2026-09-02T11:15:00Z");
        // Einfuegereihenfolge bewusst NICHT chronologisch (t2, t3, t1).
        MailDelivery delivery = seedDelivery("Bob Beispiel", "bob@example.invalid", "Sicherheitshinweis",
                "hinweis.pdf", TrackingTokens.hash("bob-token"), DeliveryStatus.SENT,
                Instant.parse("2026-09-01T07:00:00Z"), 1, t2, t3, t1);

        MvcResult result = mockMvc.perform(get("/tracking/delivery/{id}", delivery.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ja")))
                .andReturn();

        TrackingDeliveryService.DeliveryDetail detail = detailOf(result);
        assertThat(detail.triggered()).isTrue();
        assertThat(detail.actionCount()).isEqualTo(3L);
        assertThat(detail.timeline()).hasSize(3);
        // Chronologisch aufsteigend im Modell.
        assertThat(detail.timeline()).extracting(TrackingDeliveryService.TimelineEntry::occurredAt)
                .containsExactly(t1, t2, t3);
        assertThat(detail.firstAction()).isEqualTo(t1);
        assertThat(detail.lastAction()).isEqualTo(t3);

        // Chronologisch aufsteigend auch im gerenderten HTML. Bewusst nur im Timeline-Bereich pruefen, da der
        // erste/letzte Zeitpunkt zusaetzlich in der Tracking-Zusammenfassung erscheint (t1 als "Erster", t3 als
        // "Letzter Zeitpunkt") und ein globales indexOf sonst diese Vorkommen treffen wuerde.
        String html = result.getResponse().getContentAsString();
        String timelineHtml = html.substring(html.indexOf("Ereignis-Timeline"));
        String f1 = trackingTimeFormat.format(t1);
        String f2 = trackingTimeFormat.format(t2);
        String f3 = trackingTimeFormat.format(t3);
        assertThat(timelineHtml).contains(f1).contains(f2).contains(f3);
        assertThat(timelineHtml.indexOf(f1)).isLessThan(timelineHtml.indexOf(f2));
        assertThat(timelineHtml.indexOf(f2)).isLessThan(timelineHtml.indexOf(f3));
    }

    // Getrackte Zustellung ohne Ereignis -> triggered "Nein", leerer Timeline-Zustand, keine Exception.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void trackedDeliveryWithoutEventsShowsEmptyState() throws Exception {
        MailDelivery delivery = seedDelivery("Carol Beispiel", "carol@example.invalid", "Newsletter",
                "info.docx", TrackingTokens.hash("carol-token"), DeliveryStatus.SENT,
                Instant.parse("2026-09-03T12:00:00Z"), 1);

        MvcResult result = mockMvc.perform(get("/tracking/delivery/{id}", delivery.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nein")))
                .andExpect(content().string(containsString("Noch keine Ereignisse.")))
                .andReturn();

        TrackingDeliveryService.DeliveryDetail detail = detailOf(result);
        assertThat(detail.triggered()).isFalse();
        assertThat(detail.actionCount()).isZero();
        assertThat(detail.timeline()).isEmpty();
        assertThat(detail.firstAction()).isNull();
        assertThat(detail.lastAction()).isNull();
    }

    // Zustellung ohne Anhang und Status NOT_SENT (kein sentAt) -> rendert mit Geviertstrichen, keine Exception.
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void notSentDeliveryWithoutAttachmentRendersEmDashes() throws Exception {
        MailDelivery delivery = seedDelivery("Dave Beispiel", "dave@example.invalid", "Entwurf",
                null, TrackingTokens.hash("dave-token"), DeliveryStatus.NOT_SENT, null, 0);

        MvcResult result = mockMvc.perform(get("/tracking/delivery/{id}", delivery.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("dave@example.invalid")))
                // Geviertstrich (—) fuer fehlenden Anhang und fehlenden Versandzeitpunkt.
                .andExpect(content().string(containsString("—")))
                .andExpect(content().string(containsString("NOT_SENT")))
                .andReturn();

        TrackingDeliveryService.DeliveryDetail detail = detailOf(result);
        assertThat(detail.attachmentFilename()).isNull();
        assertThat(detail.sentAt()).isNull();
        assertThat(detail.status()).isEqualTo(DeliveryStatus.NOT_SENT);
    }

    // #25: unbekannte Id -> kontrollierte 404-Antwort (kein Stacktrace/Leak, kein roher 500er).
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void unknownIdReturnsControlledNotFound() throws Exception {
        MvcResult result = mockMvc.perform(get("/tracking/delivery/{id}", 999_999L))
                .andExpect(status().isNotFound())
                .andReturn();

        // Kein Leak interner Details (Stacktrace/Exception-Klasse) im Antwortkoerper.
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("DeliveryNotFoundException");
        assertThat(body).doesNotContain("Exception");
        assertThat(body).doesNotContain("at de.internal.awareness");
    }

    // #3/#4/#26: WEDER Klartext-Token NOCH SHA-256-Hash NOCH User-Agent/IP duerfen im Admin-HTML erscheinen
    // (nicht vakuum-wahr: die Detailseite wird mit sichtbarer E-Mail tatsaechlich gerendert).
    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void detailNeverRendersTokenHashOrTelemetry() throws Exception {
        MailDelivery delivery = seedDelivery("Erin Beispiel", "erin@example.invalid", "Passwort zuruecksetzen",
                "reset.docx", TOKEN_HASH, DeliveryStatus.SENT,
                Instant.parse("2026-09-04T06:00:00Z"), 1,
                Instant.parse("2026-09-04T07:00:00Z"));

        mockMvc.perform(get("/tracking/delivery/{id}", delivery.getId()))
                .andExpect(status().isOk())
                // Beleg, dass die Seite wirklich gerendert wird (Assertion nicht vakuum-wahr) ...
                .andExpect(content().string(containsString("erin@example.invalid")))
                // ... aber WEDER Klartext-Token NOCH Hash NOCH Telemetrie (User-Agent) erscheinen.
                .andExpect(content().string(not(containsString(RAW_TOKEN))))
                .andExpect(content().string(not(containsString(TOKEN_HASH))))
                .andExpect(content().string(not(containsString("User-Agent"))));
    }
}
