package de.internal.awareness.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-closed auf Anwendungsebene: OHNE konfigurierte Admin-Zugangsdaten (leerer Benutzername/leeres Passwort)
 * darf der Admin-Bereich NICHT offen sein - geschuetzte Seiten verlangen weiterhin Login, und da kein Benutzer
 * existiert, scheitert jede Anmeldung. So wird verhindert, dass fehlende Konfiguration den Admin-Bereich oeffnet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.admin.username=",
        "app.admin.password="
})
class AdminSecurityFailClosedTest {

    @Autowired
    private MockMvc mockMvc;

    // 27
    @Test
    void adminAreaIsNotOpenWhenNoCredentialsConfigured() throws Exception {
        mockMvc.perform(get("/campaigns"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // 27/28/29 (end-to-end): ohne konfigurierten Benutzer scheitert jede Anmeldung.
    @Test
    void anyLoginFailsWhenNoCredentialsConfigured() throws Exception {
        mockMvc.perform(formLogin().user("irgendwer").password("irgendwas"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }
}
