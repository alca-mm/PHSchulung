package de.internal.awareness.system;

import jakarta.mail.AuthenticationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Fuehrt einen sicheren SMTP-Verbindungstest durch, der KEINE E-Mail versendet und unabhaengig von
 * {@code APP_MAIL_LIVE_SEND_ENABLED} funktioniert.
 *
 * <p>Sicherheit: Rohe Ausnahmen/Serverantworten/Stacktraces/Credentials werden NIEMALS zurueckgegeben oder
 * geloggt - das Ergebnis ist ausschliesslich eine feste, sanitizte {@link SmtpTestOutcome}-Kategorie. Das
 * SMTP-Passwort wird nicht als Feld gehalten (nur seine Existenz als boolean), nicht geloggt und nicht
 * zurueckgegeben. Der eigentliche Verbindungsaufbau ist ueber {@link SmtpConnectionProbe} gekapselt (in
 * Tests deterministisch, ohne echtes SMTP).</p>
 */
@Service
public class SmtpConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(SmtpConnectionTester.class);

    /** Obergrenze fuer das Durchlaufen der Ursachenkette (Schutz gegen zyklische Cause-Referenzen). */
    private static final int MAX_CAUSE_DEPTH = 20;

    private final SmtpConnectionProbe probe;
    private final boolean hostConfigured;
    private final boolean authEnabled;
    private final boolean usernamePresent;
    private final boolean passwordPresent;

    public SmtpConnectionTester(
            SmtpConnectionProbe probe,
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.properties.mail.smtp.auth:true}") boolean authEnabled,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password) {
        this.probe = probe;
        this.hostConfigured = StringUtils.hasText(host);
        this.authEnabled = authEnabled;
        this.usernamePresent = StringUtils.hasText(username);
        this.passwordPresent = StringUtils.hasText(password);
    }

    /**
     * Prueft die Verbindung/Authentifizierung zum konfigurierten SMTP-Server. Versendet nichts. Liefert eine
     * sanitizte Kategorie; bei unvollstaendiger Konfiguration wird gar nicht erst verbunden.
     */
    public SmtpTestResult test() {
        if (!hostConfigured) {
            return SmtpTestResult.of(SmtpTestOutcome.INCOMPLETE_CONFIG);
        }
        if (authEnabled && (!usernamePresent || !passwordPresent)) {
            return SmtpTestResult.of(SmtpTestOutcome.INCOMPLETE_CONFIG);
        }
        try {
            probe.connect();
            return SmtpTestResult.of(SmtpTestOutcome.SUCCESS);
        } catch (Exception ex) {
            SmtpTestOutcome outcome = classify(ex);
            // Datensparsam: nur die Kategorie loggen - nie die Ausnahme-Meldung, Serverantwort oder Credentials.
            log.warn("SMTP-Verbindungstest fehlgeschlagen: Kategorie={}", outcome);
            return SmtpTestResult.of(outcome);
        }
    }

    /**
     * Bildet eine Ausnahme anhand ihres Typs (und der Ursachenkette) auf eine sichere Kategorie ab. Es wird
     * bewusst NIE die Ausnahme-Meldung ausgewertet.
     */
    static SmtpTestOutcome classify(Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            if (current instanceof AuthenticationFailedException) {
                return SmtpTestOutcome.AUTH_FAILED;
            }
            if (current instanceof SSLException) {
                return SmtpTestOutcome.TLS_FAILED;
            }
            if (current instanceof SocketTimeoutException) {
                return SmtpTestOutcome.TIMEOUT;
            }
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException) {
                return SmtpTestOutcome.HOST_UNREACHABLE;
            }
            Throwable cause = current.getCause();
            current = (cause == current) ? null : cause;
        }
        return SmtpTestOutcome.OTHER_ERROR;
    }
}
