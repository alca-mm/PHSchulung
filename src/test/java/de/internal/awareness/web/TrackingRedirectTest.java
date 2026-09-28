package de.internal.awareness.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.internal.awareness.config.AppTrackingProperties;
import de.internal.awareness.contact.Contact;
import de.internal.awareness.contact.ContactRepository;
import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailBatchRepository;
import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.mail.MailDeliveryRepository;
import de.internal.awareness.tracking.MailTrackingEventRepository;
import de.internal.awareness.tracking.TrackingLinkPolicy;
import de.internal.awareness.tracking.TrackingTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Vollstack-Tests (Server + Persistenz) des oeffentlichen Trainingslink-Endpoints {@code GET /t/{token}} mit
 * konfigurierter Umleitung auf eine oeffentliche, statische Landingpage.
 *
 * <p>Nachgewiesen wird: Bei gueltigem Token und sicherer {@code app.tracking.redirect-url} antwortet der
 * Endpoint mit HTTP-302 auf GENAU die konfigurierte URL, waehrend serverseitig weiterhin GENAU EIN
 * {@code LINK_CLICK}-Ereignis mit serverseitig gesetztem Zeitpunkt entsteht. Das Umleitungsziel stammt
 * ausschliesslich aus der Konfiguration und niemals aus Request-Parametern (kein Open-Redirect). Unbekannte
 * Tokens werden neutral mit 404 beantwortet - ohne Umleitung, ohne Ereignis und ohne Datenleck. Der
 * Klartext-Token erscheint nicht in den Logs. Ohne bzw. mit unsicherer Umleitung bleibt das bisherige
 * Verhalten (Trainingsseite) erhalten (Abwaertskompatibilitaet).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TrackingRedirectTest {

    /** Oeffentliche, statische Landingpage (KEIN Secret) - bewusst eine echte github.io-HTTPS-URL. */
    private static final String REDIRECT_URL = "https://alca-mm.github.io/PHSchulung/";

    /** Isoliertes Verzeichnis, damit nie ./data/generated-files angelegt wird - fuer /t/ ansonsten irrelevant. */
    @TempDir
    static Path generatedDir;

    /** Umleitung wird per @DynamicPropertySource aus der Konfiguration gebunden (kein realer Dateizugriff). */
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.files.generated-dir", () -> generatedDir.toString());
        registry.add("app.tracking.redirect-url", () -> REDIRECT_URL);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppTrackingProperties trackingProperties;

    @Autowired
    private MailBatchRepository batchRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private MailDeliveryRepository deliveryRepository;

    @Autowired
    private MailTrackingEventRepository eventRepository;

    private record Prepared(MailDelivery delivery, String token, String hash, String email) {
    }

    private Prepared prepareDelivery(String email) {
        MailBatch batch = batchRepository.saveAndFlush(new MailBatch(
                "Betreff", "Hallo", "training@example.invalid", null, null, null, 1));
        Contact contact = contactRepository.saveAndFlush(new Contact(email, "Name"));
        TrackingTokens.GeneratedToken token = TrackingTokens.generate();
        MailDelivery delivery = deliveryRepository.saveAndFlush(
                new MailDelivery(batch, contact, token.tokenHash()));
        return new Prepared(delivery, token.token(), token.tokenHash(), email);
    }

    @BeforeEach
    void configureRedirect() {
        // Ausgangszustand jedes Tests: sichere Umleitung konfiguriert (Bean-Mutation wie in anderen Web-Tests).
        trackingProperties.setRedirectUrl(REDIRECT_URL);
    }

    // spec #1..#5: gueltiger Token -> HTTP-302 auf GENAU die konfigurierte URL, GENAU EIN neues Ereignis mit
    // serverseitig gesetztem occurredAt (nahe jetzt). Die github.io-HTTPS-URL wird als Ziel akzeptiert.
    @Test
    void validTokenRedirectsToConfiguredLandingPageAndRecordsExactlyOneEvent() throws Exception {
        // Vorbedingung dokumentiert: die konfigurierte HTTPS-URL ist ein zulaessiges Umleitungsziel.
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl(REDIRECT_URL)).isTrue();

        Prepared p = prepareDelivery("a@example.invalid");
        long before = eventRepository.countByDelivery(p.delivery());
        Instant tsBefore = Instant.now();

        mockMvc.perform(get("/t/{token}", p.token()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(REDIRECT_URL))
                .andExpect(header().string("Location", REDIRECT_URL))
                // No-Cache-Header gelten auch fuer die Umleitungsantwort.
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        Instant tsAfter = Instant.now();
        assertThat(eventRepository.countByDelivery(p.delivery())).isEqualTo(before + 1L);

        Instant occurredAt = eventRepository.findFirstByDeliveryOrderByOccurredAtDesc(p.delivery())
                .orElseThrow().getOccurredAt();
        assertThat(occurredAt)
                .as("occurredAt wird serverseitig gesetzt und liegt nahe jetzt")
                .isBetween(tsBefore.minusSeconds(5), tsAfter.plusSeconds(5));
    }

    // spec #8: Das Umleitungsziel wird NIE aus Request-Parametern uebernommen (kein Open-Redirect).
    @Test
    void redirectTargetIsNeverTakenFromRequestParameters() throws Exception {
        Prepared p = prepareDelivery("a@example.invalid");

        mockMvc.perform(get("/t/{token}", p.token())
                        .param("redirect", "https://evil.invalid/")
                        .param("url", "https://evil.invalid/")
                        .param("next", "https://evil.invalid/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", REDIRECT_URL))
                .andExpect(header().string("Location", not(containsString("evil.invalid"))));
    }

    // spec #6/#7: unbekannter Token -> keine Umleitung, 404, kein Ereignis, kein Datenleck im Body.
    @Test
    void unknownTokenReturnsNotFoundWithoutRedirectOrLeak() throws Exception {
        Prepared p = prepareDelivery("secret.person@example.invalid");
        String unknown = TrackingTokens.generate().token(); // gueltiges Format, aber nicht vergeben

        mockMvc.perform(get("/t/{token}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(view().name("tracking/invalid"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(not(containsString(p.email()))))
                .andExpect(content().string(not(containsString(p.token()))))
                .andExpect(content().string(not(containsString(p.hash()))))
                // Hinweis: die numerische Delivery-Id (z. B. "1") wird bewusst NICHT geprueft - einstellige
                // Werte kollidieren mit beilaeufigem Seiteninhalt (CSS: 1px, 1.5 ...) und die neutrale
                // Fehlerseite rendert ohnehin keine zustellungsspezifischen Daten.
                .andExpect(content().string(not(containsString("delivery"))))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("at de.internal."))));

        assertThat(eventRepository.countByDelivery(p.delivery())).isZero();
    }

    // Ungueltiges Tokenformat (zu lang) -> ebenfalls neutral 404, keine Umleitung.
    @Test
    void malformedTokenReturnsNotFoundWithoutRedirect() throws Exception {
        mockMvc.perform(get("/t/{token}", "a".repeat(300)))
                .andExpect(status().isNotFound())
                .andExpect(view().name("tracking/invalid"))
                .andExpect(header().doesNotExist("Location"));
    }

    // spec #12: Bei einem erfolgreichen Klick erscheint der Klartext-Token NICHT in den Logs (deliveryId ist ok).
    @Test
    void rawTokenIsNotWrittenToLogsOnSuccessfulClick() throws Exception {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previousLevel = rootLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
        rootLogger.setLevel(Level.INFO); // sicherstellen, dass die INFO-Klickmeldung erfasst wird
        try {
            Prepared p = prepareDelivery("a@example.invalid");

            mockMvc.perform(get("/t/{token}", p.token()))
                    .andExpect(status().is3xxRedirection());

            // Kein einziger Logeintrag darf den Klartext-Token enthalten (weder formatiert noch als Argument).
            for (ILoggingEvent event : appender.list) {
                assertThat(event.getFormattedMessage())
                        .as("Logzeile darf den Klartext-Token nicht enthalten")
                        .doesNotContain(p.token());
                assertThat(String.valueOf(event.getMessage())).doesNotContain(p.token());
            }

            // Gegenprobe: der Klick wurde ueberhaupt geloggt (nur deliveryId), sonst waere der Test trivial gruen.
            boolean clickLogged = appender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("deliveryId="));
            assertThat(clickLogged)
                    .as("die Klickmeldung (mit deliveryId, ohne Token) muss erfasst worden sein")
                    .isTrue();
        } finally {
            rootLogger.detachAppender(appender);
            appender.stop();
            rootLogger.setLevel(previousLevel);
        }
    }

    /**
     * Abwaertskompatibilitaet: ohne konfigurierte Umleitung bleibt das bisherige Verhalten (Trainingsseite mit
     * HTTP-200 und ein Klick-Ereignis) erhalten. Eigener Kontextzustand ueber die Bean-Mutation im @BeforeEach.
     */
    @Nested
    class WhenRedirectNotConfigured {

        @BeforeEach
        void clearRedirect() {
            trackingProperties.setRedirectUrl("");
        }

        @Test
        void emptyRedirectShowsTrainingPageAndCreatesEvent() throws Exception {
            Prepared p = prepareDelivery("a@example.invalid");

            mockMvc.perform(get("/t/{token}", p.token()))
                    .andExpect(status().isOk())
                    .andExpect(view().name("tracking/training"))
                    .andExpect(content().string(containsString("Security Awareness Training")))
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(header().string("Cache-Control", containsString("no-store")));

            assertThat(eventRepository.countByDelivery(p.delivery())).isEqualTo(1L);
        }
    }

    /**
     * Eine unsichere konfigurierte Umleitung (z. B. {@code javascript:}) wird NIE als Location ausgegeben,
     * sondern faellt sicher auf die Trainingsseite zurueck (fail-closed, kein XSS/Redirect-Missbrauch).
     */
    @Nested
    class WhenRedirectMisconfigured {

        @BeforeEach
        void setUnsafeRedirect() {
            trackingProperties.setRedirectUrl("javascript:alert(1)");
        }

        @Test
        void unsafeRedirectFallsBackToTrainingPage() throws Exception {
            Prepared p = prepareDelivery("a@example.invalid");

            mockMvc.perform(get("/t/{token}", p.token()))
                    .andExpect(status().isOk())
                    .andExpect(view().name("tracking/training"))
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(content().string(not(containsString("javascript:alert(1)"))));
        }
    }
}
