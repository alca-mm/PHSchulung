package de.internal.awareness.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.internal.awareness.support.ConfigurableSmtpConnectionProbe;
import de.internal.awareness.support.RecordingJavaMailSender;
import de.internal.awareness.support.TestMailConfig;
import de.internal.awareness.system.SmtpTestOutcome;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * MVC-Tests der Systemseite und des SMTP-Verbindungstests. Voller Stack ueber MockMvc; der Verbindungsaufbau
 * ist ueber {@link ConfigurableSmtpConnectionProbe} deterministisch (ohne echtes SMTP), und
 * {@link RecordingJavaMailSender} belegt, dass beim Test NIE eine Mail versendet wird.
 *
 * <p>Sicherheitsfokus: Weder SMTP- noch Admin-Secrets erscheinen im HTML oder in Logs; Passwoerter werden
 * nur als "Ja/Nein" angezeigt; Fehlermeldungen des Verbindungstests sind sanitizte Standardtexte ohne rohe
 * Serverantworten/Credentials. Es werden ausschliesslich fiktive {@code example.invalid}-Werte verwendet.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({TestMailConfig.class, SystemControllerTest.Probes.class})
@TestPropertySource(properties = {
        "spring.mail.host=smtp.example.invalid",
        "spring.mail.username=smtp-user",
        "spring.mail.password=DoNotLeakThisSmtpSecret",
        "spring.mail.properties.mail.smtp.auth=true",
        "app.admin.username=test-admin",
        "app.admin.password=DoNotLeakThisAdminSecret"
})
@org.springframework.test.context.TestExecutionListeners(listeners = org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener.class, mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@WithMockUser(username = "test-admin", roles = "ADMIN")
class SystemControllerTest {

    private static final String SMTP_SECRET = "DoNotLeakThisSmtpSecret";
    private static final String ADMIN_SECRET = "DoNotLeakThisAdminSecret";

    @TempDir
    static Path filesDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.files.generated-dir", () -> filesDir.toString());
    }

    /** Stellt das Verbindungs-Test-Double als vorrangige {@code SmtpConnectionProbe} bereit. */
    @TestConfiguration
    static class Probes {
        @Bean
        @Primary
        ConfigurableSmtpConnectionProbe probe() {
            return new ConfigurableSmtpConnectionProbe();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConfigurableSmtpConnectionProbe probe;

    @Autowired
    private RecordingJavaMailSender recordingJavaMailSender;

    @BeforeEach
    void setUp() {
        recordingJavaMailSender.reset();
        // Standardmaessig erfolgreicher Verbindungsaufbau; einzelne Tests setzen ihre eigene Fehlerregel.
        probe.succeed();
    }

    @Test
    void systemPageIsRenderedForAuthenticatedAdmin() throws Exception {
        mockMvc.perform(get("/system"))
                .andExpect(status().isOk())
                .andExpect(view().name("system/status"))
                .andExpect(content().string(containsString("System")));
    }

    @Test
    void systemPageContainsNoSmtpOrAdminSecret() throws Exception {
        MvcResult result = mockMvc.perform(get("/system"))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString();
        assertThat(html).doesNotContain(SMTP_SECRET);
        assertThat(html).doesNotContain(ADMIN_SECRET);
    }

    @Test
    void passwordIsShownOnlyAsYesNo() throws Exception {
        mockMvc.perform(get("/system"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Passwort konfiguriert")))
                .andExpect(content().string(not(containsString(SMTP_SECRET))));
    }

    @Test
    void testSmtpSuccessRedirectsWithFlashAndSendsNoMail() throws Exception {
        probe.succeed();
        mockMvc.perform(post("/system/test-smtp").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/system"))
                .andExpect(flash().attribute("smtpTestSuccess", true));

        // Belegt: der Verbindungstest versendet KEINE Mail (weder einfache noch MIME-Nachrichten).
        assertThat(recordingJavaMailSender.getSentCount()).isZero();
        assertThat(recordingJavaMailSender.getSentMimeCount()).isZero();
    }

    @Test
    void testSmtpAuthFailedReturnsSanitizedMessage() throws Exception {
        probe.failWith(new AuthenticationFailedException("535 secret"));

        MvcResult result = mockMvc.perform(post("/system/test-smtp").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/system"))
                .andExpect(flash().attribute("smtpTestSuccess", false))
                .andExpect(flash().attribute("smtpTestMessage", SmtpTestOutcome.AUTH_FAILED.message()))
                .andReturn();

        String message = (String) result.getFlashMap().get("smtpTestMessage");
        assertThat(message).doesNotContain("535").doesNotContain("secret");
        assertThat(recordingJavaMailSender.getSentCount()).isZero();
    }

    @Test
    void testSmtpHostUnreachableReturnsCategoryMessage() throws Exception {
        probe.failWith(new MessagingException("x", new ConnectException()));

        mockMvc.perform(post("/system/test-smtp").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/system"))
                .andExpect(flash().attribute("smtpTestSuccess", false))
                .andExpect(flash().attribute("smtpTestMessage", SmtpTestOutcome.HOST_UNREACHABLE.message()));
    }

    @Test
    void testSmtpTimeoutReturnsCategoryMessage() throws Exception {
        probe.failWith(new MessagingException("x", new SocketTimeoutException()));

        mockMvc.perform(post("/system/test-smtp").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/system"))
                .andExpect(flash().attribute("smtpTestSuccess", false))
                .andExpect(flash().attribute("smtpTestMessage", SmtpTestOutcome.TIMEOUT.message()));
    }

    @Test
    void testSmtpNeverLeaksSecretIntoLogs() throws Exception {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
        try {
            // Die Ausnahme-Meldung enthaelt bewusst das Secret; es darf nirgends geloggt werden.
            probe.failWith(new AuthenticationFailedException("535 " + SMTP_SECRET));
            mockMvc.perform(post("/system/test-smtp").with(csrf()))
                    .andExpect(status().is3xxRedirection());
        } finally {
            rootLogger.detachAppender(appender);
            appender.stop();
        }
        assertThat(appender.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains(SMTP_SECRET));
    }

    @Test
    void testSmtpRunsEvenWhenLiveSendIsDisabledByDefault() throws Exception {
        // Live-Send wird bewusst NICHT aktiviert (Default false); der Verbindungstest laeuft dennoch.
        probe.succeed();
        mockMvc.perform(post("/system/test-smtp").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/system"))
                .andExpect(flash().attribute("smtpTestSuccess", true));

        assertThat(recordingJavaMailSender.getSentCount()).isZero();
    }
}
