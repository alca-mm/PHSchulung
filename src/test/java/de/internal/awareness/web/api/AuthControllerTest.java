package de.internal.awareness.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.internal.awareness.api.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integrationstests der Cross-Origin-Auth-Endpunkte ({@code /api/auth/**}) auf dem zustandslosen API-Chain:
 * fehlender Token -> {@code 401} (KEIN Redirect), generische {@code 401} bei falschen Zugangsdaten (ohne Token
 * im Body), erfolgreicher Login mit nicht-leerem Token, Logout-Widerruf, sowie dass weder Passwort noch
 * {@code trackingTokenHash}/{@code password} jemals in einer Antwort erscheinen.
 *
 * <p>Zusaetzlich die In-Memory-Anmelde-Drossel ({@link LoginAttemptService}): zu viele Fehlversuche fuehren zu
 * {@code 429} ({@code too_many_attempts}) mit {@code Retry-After}; ein Erfolg setzt den Zaehler zurueck; die
 * 429-Antwort ist fuer existierende und nicht existierende Benutzer identisch (keine User-Enumeration). Die
 * Grenze wird per {@code @TestPropertySource} klein (3) gehalten und die globale Grenze bewusst hoch, damit die
 * Pro-Benutzer-Tests deterministisch sind; die Drossel wird vor jedem Test zurueckgesetzt (gemeinsamer
 * Singleton-Bean im gecachten Kontext).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.admin.username=test-admin",
        "app.admin.password=Test-Passwort-123!",
        "app.api.login-max-attempts=3",
        "app.api.login-window-seconds=300",
        "app.api.login-lock-seconds=300",
        "app.api.login-global-max-attempts=1000"
})
class AuthControllerTest {

    private static final String ADMIN = "test-admin";
    private static final String PW = "Test-Passwort-123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoginAttemptService loginAttemptService;

    /** Gemeinsamer Singleton-Zaehler im gecachten Kontext -> vor jedem Test leeren (Testisolation). */
    @BeforeEach
    void resetThrottle() {
        loginAttemptService.reset();
    }

    /** Baut einen JSON-Login-Body (Werte hier ohne Sonderzeichen; einfache Konkatenation genuegt). */
    private static String loginBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    private String loginAndExtractToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(ADMIN, PW)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private void postWrong(String username) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, "falsches-passwort")))
                .andExpect(status().isUnauthorized());
    }

    // #1: geschuetzter Endpoint OHNE Token -> 401 (kein Redirect auf /login).
    @Test
    void meWithoutTokenIsUnauthorizedNotRedirect() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
    }

    // #2: falsche Zugangsdaten -> 401, KEIN Token im Body.
    @Test
    void wrongCredentialsAreUnauthorizedWithoutToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(ADMIN, "falsch")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("token").doesNotContain("falsch");
    }

    // #2b: unbekannter Benutzer -> identische generische 401.
    @Test
    void unknownUserFailsIdenticallyGeneric() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("gibtsnicht", "egal")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("token"))));
    }

    // #3: korrekte Zugangsdaten -> 200 + nicht-leerer Token + Benutzername + expiresInSeconds > 0.
    @Test
    void correctCredentialsReturnToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(ADMIN, PW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value(ADMIN))
                .andExpect(jsonPath("$.expiresInSeconds", greaterThan(0)));
    }

    // #3b: mit gueltigem Token ist /api/auth/me erreichbar (200) und liefert den Benutzernamen.
    @Test
    void meWithValidTokenReturnsUsername() throws Exception {
        String token = loginAndExtractToken();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(ADMIN));
    }

    // #4 + #5: nach Logout ist derselbe Token ungueltig -> /api/auth/me = 401.
    @Test
    void logoutRevokesTokenSoMeIsUnauthorized() throws Exception {
        String token = loginAndExtractToken();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // #6: das Passwort taucht in KEINER Antwort auf (Erfolg wie Fehlerfall).
    @Test
    void passwordNeverAppearsInAnyResponse() throws Exception {
        MvcResult ok = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(ADMIN, PW)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(ok.getResponse().getContentAsString()).doesNotContain(PW);

        MvcResult bad = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(ADMIN, PW + "-falsch")))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(bad.getResponse().getContentAsString()).doesNotContain(PW);
    }

    // #7: die Login-Antwort enthaelt weder "trackingTokenHash" noch ein "password"-Feld.
    @Test
    void responseHasNoTrackingHashOrPasswordField() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(ADMIN, PW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.trackingTokenHash").doesNotExist())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("trackingTokenHash").doesNotContain("password");
    }

    // #8: fehlende Felder -> 400 (Bean Validation), generische ApiError.
    @Test
    void missingFieldsAreBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    // --- Anmelde-Drossel (Login-Throttle) ---------------------------------------------------------

    // #T1: wiederholte falsche Passwoerter -> nach Erreichen der Grenze 429 mit Retry-After + too_many_attempts.
    @Test
    void repeatedWrongPasswordEventuallyReturns429WithRetryAfter() throws Exception {
        // Grenze = 3: die ersten drei Fehlversuche liefern noch 401 (Zaehler erreicht danach die Grenze).
        for (int i = 0; i < 3; i++) {
            postWrong(ADMIN);
        }

        // Der naechste Versuch ist gesperrt -> 429, generische Fehlerantwort, Retry-After vorhanden.
        MvcResult blocked = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(ADMIN, PW)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("too_many_attempts"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andReturn();

        // Retry-After ist eine positive Sekundenzahl; kein Token/Passwort im Body.
        String retryAfter = blocked.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isPositive();
        assertThat(blocked.getResponse().getContentAsString())
                .doesNotContain("token")
                .doesNotContain(PW);
    }

    // #T2: ein erfolgreicher Login setzt den Zaehler zurueck (falsch x(N-1), dann korrekt, Zaehler geleert).
    @Test
    void successfulLoginResetsThrottleCounter() throws Exception {
        // Zwei Fehlversuche (< Grenze 3) -> noch nicht gesperrt.
        postWrong(ADMIN);
        postWrong(ADMIN);

        // Korrekter Login -> 200 und Zaehler-Reset.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(ADMIN, PW)))
                .andExpect(status().isOk());

        // Ohne Reset waere jetzt (2 + 2 = 4 >= 3) der zweite Fehlversuch gesperrt; mit Reset bleiben es 401.
        postWrong(ADMIN);
        postWrong(ADMIN);
    }

    // #T3: die 429-Antwort ist fuer existierende und nicht existierende Benutzer identisch (keine Enumeration).
    @Test
    void throttleResponseIsIdenticalForExistingAndNonexistingUser() throws Exception {
        String existingBody = block(ADMIN);
        String nonexistingBody = block("gibt-es-nicht");
        assertThat(nonexistingBody).isEqualTo(existingBody);
    }

    /** Sperrt den (per-Benutzer-)Zaehler durch N Fehlversuche und liefert den Body der folgenden 429-Antwort. */
    private String block(String username) throws Exception {
        for (int i = 0; i < 3; i++) {
            postWrong(username);
        }
        MvcResult blocked = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, "egal")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("too_many_attempts"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andReturn();
        return blocked.getResponse().getContentAsString();
    }
}
