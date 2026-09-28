package de.internal.awareness.system;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure Unit-Tests des SMTP-Verbindungstests: Vollstaendigkeitspruefung, Klassifizierung von Fehlern in
 * sichere Kategorien und der Nachweis, dass die Ergebnismeldung keine rohen Details/Secrets enthaelt. Es
 * wird NIE echtes SMTP verwendet (der Verbindungsaufbau ist ueber {@link SmtpConnectionProbe} gekapselt).
 */
class SmtpConnectionTesterTest {

    /** Probe, die stets erfolgreich verbindet und mitzaehlt, wie oft sie aufgerufen wurde. */
    private static final class SuccessProbe implements SmtpConnectionProbe {
        int calls = 0;
        @Override public void connect() {
            calls++;
        }
    }

    private static SmtpConnectionProbe failing(MessagingException ex) {
        return () -> {
            throw ex;
        };
    }

    private SmtpConnectionTester tester(SmtpConnectionProbe probe, String host, boolean auth,
                                        String username, String password) {
        return new SmtpConnectionTester(probe, host, auth, username, password);
    }

    @Test
    void incompleteWhenHostMissing() {
        SuccessProbe probe = new SuccessProbe();
        SmtpTestResult result = tester(probe, "", true, "user", "pass").test();
        assertThat(result.outcome()).isEqualTo(SmtpTestOutcome.INCOMPLETE_CONFIG);
        assertThat(probe.calls).as("bei unvollstaendiger Konfiguration wird nicht verbunden").isZero();
    }

    @Test
    void incompleteWhenAuthButUsernameOrPasswordMissing() {
        assertThat(tester(new SuccessProbe(), "smtp.example.invalid", true, "", "pass").test().outcome())
                .isEqualTo(SmtpTestOutcome.INCOMPLETE_CONFIG);
        assertThat(tester(new SuccessProbe(), "smtp.example.invalid", true, "user", "").test().outcome())
                .isEqualTo(SmtpTestOutcome.INCOMPLETE_CONFIG);
    }

    @Test
    void successWhenProbeConnects() {
        SuccessProbe probe = new SuccessProbe();
        SmtpTestResult result = tester(probe, "smtp.example.invalid", true, "user", "pass").test();
        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo(SmtpTestOutcome.SUCCESS);
        assertThat(probe.calls).isEqualTo(1);
    }

    @Test
    void withoutAuthNoCredentialsAreRequired() {
        SmtpTestResult result = tester(new SuccessProbe(), "smtp.example.invalid", false, "", "").test();
        assertThat(result.outcome()).isEqualTo(SmtpTestOutcome.SUCCESS);
    }

    @Test
    void authFailureIsMappedAndSanitized() {
        SmtpTestResult result = tester(failing(new AuthenticationFailedException("535 5.7.8 user=admin pass=supersecret")),
                "smtp.example.invalid", true, "user", "pass").test();
        assertThat(result.outcome()).isEqualTo(SmtpTestOutcome.AUTH_FAILED);
        // Die Meldung ist der feste Kategorietext - KEINE rohe Serverantwort/Credentials.
        assertThat(result.message()).isEqualTo(SmtpTestOutcome.AUTH_FAILED.message());
        assertThat(result.message()).doesNotContain("supersecret").doesNotContain("535");
    }

    @Test
    void connectExceptionIsHostUnreachable() {
        SmtpTestResult result = tester(failing(new MessagingException("fail", new ConnectException("refused"))),
                "smtp.example.invalid", true, "user", "pass").test();
        assertThat(result.outcome()).isEqualTo(SmtpTestOutcome.HOST_UNREACHABLE);
    }

    @Test
    void unknownHostIsHostUnreachable() {
        SmtpTestResult result = tester(failing(new MessagingException("fail", new UnknownHostException("nope"))),
                "smtp.example.invalid", true, "user", "pass").test();
        assertThat(result.outcome()).isEqualTo(SmtpTestOutcome.HOST_UNREACHABLE);
    }

    @Test
    void sslExceptionIsTlsFailed() {
        SmtpTestResult result = tester(failing(new MessagingException("fail", new SSLHandshakeException("bad cert"))),
                "smtp.example.invalid", true, "user", "pass").test();
        assertThat(result.outcome()).isEqualTo(SmtpTestOutcome.TLS_FAILED);
    }

    @Test
    void socketTimeoutIsTimeout() {
        SmtpTestResult result = tester(failing(new MessagingException("fail", new SocketTimeoutException("timed out"))),
                "smtp.example.invalid", true, "user", "pass").test();
        assertThat(result.outcome()).isEqualTo(SmtpTestOutcome.TIMEOUT);
    }

    @Test
    void otherErrorIsGenericCategory() {
        SmtpTestResult result = tester(failing(new MessagingException("something odd 220 banner")),
                "smtp.example.invalid", true, "user", "pass").test();
        assertThat(result.outcome()).isEqualTo(SmtpTestOutcome.OTHER_ERROR);
        assertThat(result.message()).isEqualTo(SmtpTestOutcome.OTHER_ERROR.message());
        assertThat(result.message()).doesNotContain("banner").doesNotContain("220");
    }
}
