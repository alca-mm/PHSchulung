package de.internal.awareness.web.api;

import de.internal.awareness.api.ApiTokenService;
import de.internal.awareness.contact.Contact;
import de.internal.awareness.contact.ContactRepository;
import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailBatchRepository;
import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.mail.MailDeliveryRepository;
import de.internal.awareness.tracking.TrackingTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ergaenzende Haertungs-/Regressionstests fuer den zustandslosen {@code /api/**}-Slice (Gap-Analyse R10):
 * Token-Entropie/-Format, geschuetztes Logout/{@code me} (401 statt stiller Erfolg), die Daten-nicht-Markup-
 * Zusage der JSON-API (feindliche Werte werden VERBATIM als JSON-String geliefert, niemals als HTML) sowie
 * CORS-Negativfaelle (unerlaubte Methode, erlaubter {@code Content-Type}-Header).
 *
 * <p>Wie die uebrigen API-Tests wird {@link WithMockUser} genutzt, damit die Autorisierung ueber die
 * Security-Kette greift, ohne vom Bearer-Filter abzuhaengen; {@code app.files.generated-dir} zeigt auf ein
 * {@link TempDir}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.test.context.TestExecutionListeners(
        listeners = org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener.class,
        mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class ApiHardeningTest {

    private static final String GITHUB_PAGES_ORIGIN = "https://alca-mm.github.io";

    @TempDir
    static Path generatedDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.files.generated-dir", () -> generatedDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiTokenService tokenService;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private MailBatchRepository batchRepository;

    @Autowired
    private MailDeliveryRepository deliveryRepository;

    // --- Token-Entropie/-Format -------------------------------------------------

    @Test
    void issuedTokenIsBase64Url256BitWithoutPadding() {
        String token = tokenService.issue("admin").token();
        // 256 Bit = 32 Byte, base64url ohne Padding => 43 Zeichen aus [A-Za-z0-9_-].
        assertThat(token).matches("^[A-Za-z0-9_-]{43}$");
        byte[] decoded = Base64.getUrlDecoder().decode(token);
        assertThat(decoded).hasSize(32);
        // Zwei Ausgaben unterscheiden sich (SecureRandom).
        assertThat(token).isNotEqualTo(tokenService.issue("admin").token());
    }

    // --- Geschuetzte Endpunkte: 401 statt stiller Erfolg ------------------------

    @Test
    void anonymousLogoutIsUnauthorizedNotSilent204() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void garbageBearerTokenIsUnauthorizedOnMe() throws Exception {
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer kein-gueltiger-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingTokenOnTrackingIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/tracking"))
                .andExpect(status().isUnauthorized());
    }

    // --- Daten-nicht-Markup: feindliche Werte kommen verbatim als JSON zurueck ---

    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void hostileFieldValuesAreReturnedVerbatimAsJsonNotHtml() throws Exception {
        String hostileName = "<script>alert(1)</script>";
        String hostileSubject = "\"><img src=x onerror=alert(1)>";

        MailBatch batch = batchRepository.saveAndFlush(new MailBatch(
                hostileSubject, "Hallo", "training@example.invalid", "IT Security", null, "rechnung.docx", 1));
        Contact contact = contactRepository.saveAndFlush(new Contact("victim@example.invalid", hostileName));
        MailDelivery delivery = new MailDelivery(batch, contact, TrackingTokens.generate().tokenHash());
        delivery.recordSent(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        deliveryRepository.saveAndFlush(delivery);

        MvcResult result = mockMvc.perform(get("/api/tracking"))
                .andExpect(status().isOk())
                // Antwort ist JSON-Daten, KEIN HTML (der Browser fuehrt nichts aus; das Frontend rendert per
                // textContent). Content-Type muss application/json sein, nie text/html.
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("application/json")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, not(containsString("text/html"))))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // Die feindlichen Werte erscheinen VERBATIM als String-Feldwerte (Jackson escaped '<'/'>' nicht) -
        // also als reine Daten. Das belegt: die API transformiert/rendert nichts.
        assertThat(body).contains(hostileName);
        assertThat(body).contains("onerror=alert(1)");
    }

    // --- CORS-Negativfaelle -----------------------------------------------------

    @Test
    void corsPreflightRejectsDisallowedMethod() throws Exception {
        mockMvc.perform(options("/api/tracking")
                        .header(HttpHeaders.ORIGIN, GITHUB_PAGES_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void corsPreflightAllowsContentTypeHeaderForLogin() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, GITHUB_PAGES_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, GITHUB_PAGES_ORIGIN))
                // Spring spiegelt den angefragten Header-Namen zurueck (hier klein geschrieben); case-insensitiv pruefen.
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsStringIgnoringCase("content-type")));
    }
}
