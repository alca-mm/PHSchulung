package de.internal.awareness.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
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

/**
 * Full-Stack-MVC-Tests der Betriebs-Probes ({@link HealthController}). Beide Endpunkte sind oeffentlich
 * (permitAll, siehe {@code SecurityConfig}); {@code /readiness} prueft die im Test erreichbare SQLite-DB.
 *
 * <p>Es wird ausserdem belegt, dass {@code /api/**}-Antworten mit {@code Cache-Control: no-store}
 * ausgeliefert werden (Haertung des JSON-Slice). {@code app.files.generated-dir} zeigt auf ein
 * {@link TempDir}, damit der echte {@code ./data}-Pfad nie beruehrt wird.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.TestExecutionListeners(
        listeners = org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener.class,
        mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class HealthControllerTest {

    @TempDir
    static Path generatedDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.files.generated-dir", () -> generatedDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    // --- Liveness ---------------------------------------------------------------

    @Test
    void healthIsPublicAndReportsUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsStringIgnoringCase("application/json")))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // --- Readiness --------------------------------------------------------------

    @Test
    void readinessIsPublicAndReportsUpWhenDatabaseReachable() throws Exception {
        mockMvc.perform(get("/readiness"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsStringIgnoringCase("application/json")))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // --- Oeffentlicher (anonymer) Zugriff, kein Redirect auf /login -------------

    @Test
    void probesAreReachableAnonymouslyWithoutLoginRedirect() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        mockMvc.perform(get("/readiness"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    // --- Keine Secrets/Stacktraces in den Antworten -----------------------------

    @Test
    void healthBodyIsMinimalAndContainsNoSecretsOrStacktrace() throws Exception {
        MvcResult result = mockMvc.perform(get("/health")).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).isEqualTo("{\"status\":\"UP\"}");
        assertThat(body).doesNotContain("Exception", "at de.internal.awareness", "password", "jdbc:");
    }

    @Test
    void readinessBodyContainsNoSecretsOrStacktrace() throws Exception {
        MvcResult result = mockMvc.perform(get("/readiness")).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception", "at de.internal.awareness", "password", "jdbc:");
    }

    // --- /api-Haertung: keine zwischenspeicherbaren JSON-/Auth-Antworten --------

    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void apiResponsesCarryNoStoreCacheControl() throws Exception {
        mockMvc.perform(get("/api/tracking"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        containsStringIgnoringCase("no-store")));
    }
}
