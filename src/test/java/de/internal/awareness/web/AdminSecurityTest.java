package de.internal.awareness.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin-Zugriffsschutz mit KONFIGURIERTEM Admin-Benutzer (fiktive Testwerte): geschuetzte Routen erfordern
 * Login, Login-Erfolg/-Fehler, identisches Fehlerverhalten fuer falsches Passwort/unbekannten Benutzer,
 * CSRF-Pflicht bei POST, Session/Logout und dass weder Passwort noch Credentials in Response/Logs erscheinen.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "app.admin.username=test-admin",
        "app.admin.password=Test-Passwort-123!"
})
class AdminSecurityTest {

    private static final String ADMIN = "test-admin";
    private static final String PW = "Test-Passwort-123!";

    @Autowired
    private MockMvc mockMvc;

    // 1-7: geschuetzte Routen ohne Login -> Redirect zur Loginseite.
    @ParameterizedTest
    @ValueSource(strings = {"/", "/campaigns", "/files", "/recipients", "/mail", "/mail/history",
            "/files/1/download", "/system", "/tracking"})
    void protectedRouteRedirectsToLoginWhenAnonymous(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // 8 + 24
    @Test
    void correctCredentialsLogInAndCreateAuthenticatedSession() throws Exception {
        MvcResult result = mockMvc.perform(formLogin().user(ADMIN).password(PW))
                .andExpect(authenticated().withUsername(ADMIN))
                .andExpect(redirectedUrl("/"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNotNull();
    }

    // 9
    @Test
    void wrongPasswordFails() throws Exception {
        mockMvc.perform(formLogin().user(ADMIN).password("falsch"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    // 10 (identisches Fehlerverhalten wie 9)
    @Test
    void unknownUserFailsIdentically() throws Exception {
        mockMvc.perform(formLogin().user("gibtsnicht").password("egal"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    // 11
    @Test
    void passwordNeverAppearsInResponses() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(PW))));
        mockMvc.perform(get("/login").param("error", ""))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(PW))));
    }

    // 12
    @Test
    void credentialsNeverAppearInLogs() {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
        try {
            mockMvc.perform(formLogin().user(ADMIN).password(PW));
            mockMvc.perform(formLogin().user(ADMIN).password("falsch"));
        } catch (Exception ignored) {
            // Auch bei einem unerwarteten Fehler duerfen keine Credentials im Log stehen.
        } finally {
            rootLogger.detachAppender(appender);
            appender.stop();
        }
        assertThat(appender.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains(PW));
    }

    // 18/20/21/22 + 30: POST ohne CSRF (aber authentifiziert) -> 403.
    @ParameterizedTest
    @ValueSource(strings = {"/campaigns", "/recipients", "/files", "/mail/send", "/system/test-smtp", "/mail/preview"})
    void postWithoutCsrfIsForbidden(String path) throws Exception {
        mockMvc.perform(post(path).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    // 20 + 29: POST mit CSRF, aber OHNE Login -> Redirect zur Loginseite (nicht ausgefuehrt).
    @ParameterizedTest
    @ValueSource(strings = {"/system/test-smtp", "/mail/preview", "/mail/send"})
    void postWithCsrfButAnonymousRedirectsToLogin(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // 19: POST mit CSRF + Login funktioniert (kein 403).
    @Test
    void postWithCsrfAndLoginWorks() throws Exception {
        mockMvc.perform(post("/campaigns").with(user(ADMIN).roles("ADMIN")).with(csrf())
                        .param("name", "Testkampagne")
                        .param("senderName", "IT Security")
                        .param("senderEmail", "training@example.invalid")
                        .param("emailSubject", "Betreff")
                        .param("emailBody", "Text"))
                .andExpect(status().is3xxRedirection());
    }

    // Security-Header: Clickjacking-, Content-Type-, Referrer- und CSP-Schutz aktiv.
    @Test
    void securityHeadersArePresent() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'self'")));
    }

    // 23: Thymeleaf-Formulare enthalten das CSRF-Token.
    @Test
    void formsRenderCsrfToken() throws Exception {
        mockMvc.perform(get("/campaigns/new").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    // 25 + 26: Logout invalidiert den Zugriff; danach fuehrt eine geschuetzte Seite wieder zum Login.
    @Test
    void logoutInvalidatesAccess() throws Exception {
        MvcResult login = mockMvc.perform(formLogin().user(ADMIN).password(PW))
                .andExpect(authenticated()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/campaigns").session(session)).andExpect(status().isOk());
        mockMvc.perform(post("/logout").with(csrf()).session(session))
                .andExpect(redirectedUrl("/login?logout"));
        mockMvc.perform(get("/campaigns").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}
