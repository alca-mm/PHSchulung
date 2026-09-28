package de.internal.awareness.web.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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
 * Zusammenspiel der zwei Filterketten: {@code /api/**} wird vom zustandslosen API-Chain bedient (JSON-401 ohne
 * Redirect, gueltiger Bearer-Token -> 200), waehrend alles Uebrige weiterhin vom bestehenden Web-Chain bedient
 * wird (anonyme geschuetzte Seite -> 302 /login, CSRF fuer POST aktiv). Damit ist belegt, dass die neue
 * API-Kette die bestehende Web-Kette nicht schwaecht.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.admin.username=test-admin",
        "app.admin.password=Test-Passwort-123!"
})
class ApiSecurityChainTest {

    private static final String ADMIN = "test-admin";
    private static final String PW = "Test-Passwort-123!";

    @Autowired
    private MockMvc mockMvc;

    // API-Chain: anonymer geschuetzter API-Endpoint -> 401 JSON, KEIN Redirect.
    @Test
    void anonymousApiRequestGets401JsonNotRedirect() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    // API-Chain deckt ALLE /api/**-Pfade ab (auch unbekannte) -> 401 statt 302.
    @Test
    void unknownApiPathIsHandledByApiChain() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
    }

    // Web-Chain UNVERAENDERT: anonyme geschuetzte Seite -> 302 auf /login.
    @Test
    void anonymousWebRequestStillRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/campaigns"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // Web-Chain UNVERAENDERT: POST ohne CSRF (aber authentifiziert) -> 403.
    @Test
    void webChainStillEnforcesCsrf() throws Exception {
        mockMvc.perform(post("/campaigns").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    // API-Chain: mit gueltigem Bearer-Token ist /api/auth/me erreichbar (200).
    @Test
    void validBearerTokenReachesProtectedApiEndpoint() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + ADMIN + "\",\"password\":\"" + PW + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = JsonPath.read(login.getResponse().getContentAsString(), "$.token");

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(ADMIN));
    }
}
