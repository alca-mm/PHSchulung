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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integrationstests der Cross-Origin-Auth-Endpunkte ({@code /api/auth/**}) auf dem zustandslosen API-Chain:
 * fehlender Token -> {@code 401} (KEIN Redirect), generische {@code 401} bei falschen Zugangsdaten (ohne Token
 * im Body), erfolgreicher Login mit nicht-leerem Token, Logout-Widerruf, sowie dass weder Passwort noch
 * {@code trackingTokenHash}/{@code password} jemals in einer Antwort erscheinen.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.admin.username=test-admin",
        "app.admin.password=Test-Passwort-123!"
})
class AuthControllerTest {

    private static final String ADMIN = "test-admin";
    private static final String PW = "Test-Passwort-123!";

    @Autowired
    private MockMvc mockMvc;

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
}
