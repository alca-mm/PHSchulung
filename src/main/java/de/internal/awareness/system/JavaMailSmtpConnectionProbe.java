package de.internal.awareness.system;

import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Properties;

/**
 * Standard-{@link SmtpConnectionProbe}: baut aus der konfigurierten SMTP-Konfiguration einen kurzlebigen
 * {@link JavaMailSenderImpl} und ruft {@code testConnection()} auf - das verbindet sich (inkl. Auth, falls
 * aktiviert) und schliesst die Verbindung sofort wieder, OHNE eine Mail zu senden.
 *
 * <p>Der Test ist bewusst unabhaengig von {@code APP_MAIL_LIVE_SEND_ENABLED} (er sendet nichts) und setzt
 * kurze Timeouts, damit die Oberflaeche nicht blockiert. Das SMTP-Passwort wird ausschliesslich zum
 * Verbindungsaufbau verwendet und niemals geloggt oder zurueckgegeben.</p>
 */
@Component
public class JavaMailSmtpConnectionProbe implements SmtpConnectionProbe {

    private static final int TEST_TIMEOUT_MS = 5000;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean authEnabled;
    private final boolean starttlsEnabled;

    public JavaMailSmtpConnectionProbe(
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.port:587}") int port,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password,
            @Value("${spring.mail.properties.mail.smtp.auth:true}") boolean authEnabled,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean starttlsEnabled) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.authEnabled = authEnabled;
        this.starttlsEnabled = starttlsEnabled;
    }

    @Override
    public void connect() throws MessagingException {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        if (StringUtils.hasText(username)) {
            sender.setUsername(username);
        }
        if (StringUtils.hasText(password)) {
            sender.setPassword(password);
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(authEnabled));
        props.put("mail.smtp.starttls.enable", String.valueOf(starttlsEnabled));
        props.put("mail.smtp.connectiontimeout", String.valueOf(TEST_TIMEOUT_MS));
        props.put("mail.smtp.timeout", String.valueOf(TEST_TIMEOUT_MS));
        props.put("mail.smtp.writetimeout", String.valueOf(TEST_TIMEOUT_MS));
        // Baut die Verbindung auf und schliesst sie sofort wieder - versendet KEINE Mail.
        sender.testConnection();
    }
}
