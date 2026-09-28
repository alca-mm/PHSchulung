package de.internal.awareness.web.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CORS-Verhalten des {@code /api/**}-Slice: exakte Origin-Allowlist (kein Wildcard), erlaubte Methoden/Header,
 * Preflight OHNE Auth und {@code Access-Control-Allow-Credentials} NICHT gesetzt (passend zur Bearer-Token-
 * Strategie ohne Cookies). Die erlaubte Origin wird im Test explizit gesetzt, damit er unabhaengig von der
 * Umgebung hermetisch bleibt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.admin.username=test-admin",
        "app.admin.password=Test-Passwort-123!",
        "app.api.allowed-origins=https://alca-mm.github.io"
})
class ApiCorsTest {

    private static final String ALLOWED = "https://alca-mm.github.io";
    private static final String FOREIGN = "https://evil.example";

    @Autowired
    private MockMvc mockMvc;

    // #8 + #10: erlaubte Origin -> ACAO spiegelt exakt diese Origin (nie "*").
    @Test
    void allowedOriginIsEchoedExactlyNeverWildcard() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Origin", ALLOWED))
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
    }

    // #9: fremde Origin -> KEIN Access-Control-Allow-Origin (nicht erlaubt).
    @Test
    void foreignOriginIsNotAllowed() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Origin", FOREIGN))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    // #11: Preflight OPTIONS /api/auth/login OHNE Auth -> 2xx mit passenden Allow-Methods/Headers.
    @Test
    void preflightLoginSucceedsWithoutAuth() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("Authorization")));
    }

    // #11b: Preflight fuer einen weiteren /api-Pfad (z. B. der Tracking-API von B3) wird ebenfalls vom
    // CORS-Filter behandelt - unabhaengig davon, ob der Ziel-Controller im eigenen Copy vorhanden ist.
    @Test
    void preflightForOtherApiPathSucceedsWithoutAuth() throws Exception {
        mockMvc.perform(options("/api/tracking")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("GET")));
    }

    // #12: Access-Control-Allow-Credentials wird NICHT auf true gesetzt (Bearer-Token, keine Cookies).
    @Test
    void credentialsAreNotAllowed() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Origin", ALLOWED))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));

        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}
