package de.internal.awareness.system;

import de.internal.awareness.config.AppAdminProperties;
import de.internal.awareness.config.AppMailProperties;
import de.internal.awareness.config.AppTrackingProperties;
import de.internal.awareness.config.FileStorageProperties;
import de.internal.awareness.file.FileStorageService;
import de.internal.awareness.tracking.TrackingLinkPolicy;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;

/**
 * Baut die sichere {@link SystemStatus}-Diagnose-DTO. Liest ausschliesslich nicht sensible Statuswerte aus
 * der Konfiguration/Infrastruktur; Passwoerter/Benutzer werden nur als Existenz-Booleans ermittelt und die
 * rohen Werte NICHT als Felder gehalten (nur die abgeleiteten Booleans). Es werden keine Secrets, Hashes,
 * Tokens oder Environment-Dumps erzeugt.
 */
@Service
public class SystemStatusService {

    private static final Logger log = LoggerFactory.getLogger(SystemStatusService.class);

    private final AppAdminProperties adminProperties;
    private final AppMailProperties mailProperties;
    private final AppTrackingProperties trackingProperties;
    private final FileStorageProperties fileStorageProperties;
    private final FileStorageService fileStorageService;
    private final DataSource dataSource;

    // Flyway-Status wird EINMALIG beim Start ermittelt (das Schema aendert sich zur Laufzeit nicht). So oeffnet
    // die Systemseite pro Aufruf keine zusaetzliche Flyway-Verbindung (die sonst z. B. in Tests mit einer
    // laufenden Transaktion und Pool-Groesse 1 blockieren wuerde). Nur die DB-Erreichbarkeit wird live geprueft.
    private final String schemaVersion;
    private final String expectedVersion;
    private final boolean schemaUpToDate;

    // Nur nicht sensible SMTP-Werte bzw. abgeleitete Existenz-Booleans (keine rohen Credentials als Feld).
    private final String smtpHost;
    private final int smtpPort;
    private final boolean smtpUsernamePresent;
    private final boolean smtpPasswordPresent;
    private final boolean smtpAuthEnabled;
    private final boolean smtpStarttlsEnabled;

    public SystemStatusService(AppAdminProperties adminProperties,
                               AppMailProperties mailProperties,
                               AppTrackingProperties trackingProperties,
                               FileStorageProperties fileStorageProperties,
                               FileStorageService fileStorageService,
                               Flyway flyway,
                               DataSource dataSource,
                               @Value("${spring.mail.host:}") String smtpHost,
                               @Value("${spring.mail.port:587}") int smtpPort,
                               @Value("${spring.mail.username:}") String smtpUsername,
                               @Value("${spring.mail.password:}") String smtpPassword,
                               @Value("${spring.mail.properties.mail.smtp.auth:true}") boolean smtpAuthEnabled,
                               @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean smtpStarttlsEnabled) {
        this.adminProperties = adminProperties;
        this.mailProperties = mailProperties;
        this.trackingProperties = trackingProperties;
        this.fileStorageProperties = fileStorageProperties;
        this.fileStorageService = fileStorageService;
        this.dataSource = dataSource;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpUsernamePresent = StringUtils.hasText(smtpUsername);
        this.smtpPasswordPresent = StringUtils.hasText(smtpPassword);
        this.smtpAuthEnabled = smtpAuthEnabled;
        this.smtpStarttlsEnabled = smtpStarttlsEnabled;

        // Flyway-Status einmalig beim Start erfassen (kein Request-Tx belegt hier die Verbindung).
        String version = "-";
        String expected = "-";
        boolean upToDate = false;
        try {
            MigrationInfoService info = flyway.info();
            MigrationInfo current = info.current();
            if (current != null && current.getVersion() != null) {
                version = current.getVersion().getVersion();
            }
            expected = latestResolvedVersion(info, version);
            upToDate = info.pending().length == 0;
        } catch (Exception e) {
            log.warn("Flyway-Statusermittlung beim Start fehlgeschlagen.");
        }
        this.schemaVersion = version;
        this.expectedVersion = expected;
        this.schemaUpToDate = upToDate;
    }

    /** Baut den Gesamtstatus fuer die Systemseite. {@code currentUser} ist der angemeldete Admin-Name. */
    public SystemStatus build(String currentUser) {
        SystemStatus.AdminStatus admin = buildAdmin(currentUser);
        SystemStatus.SmtpStatus smtp = buildSmtp();
        SystemStatus.SenderStatus sender = buildSender();
        SystemStatus.RecipientAllowlistStatus recipientAllowlist = buildRecipientAllowlist();
        SystemStatus.TrackingStatus tracking = buildTracking();
        SystemStatus.FileStorageStatus fileStorage = buildFileStorage();
        SystemStatus.DatabaseStatus database = buildDatabase();
        SystemStatus.OverallReadiness readiness = buildReadiness(admin, smtp, sender, tracking, fileStorage, database);
        return new SystemStatus(admin, smtp, sender, recipientAllowlist, tracking, fileStorage, database, readiness);
    }

    private SystemStatus.AdminStatus buildAdmin(String currentUser) {
        boolean configured = StringUtils.hasText(adminProperties.getUsername())
                && StringUtils.hasText(adminProperties.getPassword());
        return new SystemStatus.AdminStatus(configured, currentUser);
    }

    private SystemStatus.SmtpStatus buildSmtp() {
        boolean hostConfigured = StringUtils.hasText(smtpHost);
        boolean portValid = smtpPort > 0 && smtpPort <= 65535;
        return new SystemStatus.SmtpStatus(hostConfigured, hostConfigured ? smtpHost : null, smtpPort, portValid,
                smtpUsernamePresent, smtpPasswordPresent, smtpAuthEnabled, smtpStarttlsEnabled,
                mailProperties.isLiveSendEnabled());
    }

    private SystemStatus.SenderStatus buildSender() {
        String sender = mailProperties.getDefaultSender();
        boolean configured = StringUtils.hasText(sender);
        boolean allowed = configured && mailProperties.isSenderAllowed(sender);
        boolean allowlistConfigured = !mailProperties.getAllowedSenders().isEmpty();
        return new SystemStatus.SenderStatus(configured, sender, mailProperties.getDefaultSenderName(),
                allowed, allowlistConfigured);
    }

    private SystemStatus.RecipientAllowlistStatus buildRecipientAllowlist() {
        List<String> domains = mailProperties.getAllowedRecipientDomains();
        return new SystemStatus.RecipientAllowlistStatus(!domains.isEmpty(), List.copyOf(domains));
    }

    private SystemStatus.TrackingStatus buildTracking() {
        String baseUrl = trackingProperties.getBaseUrl();
        boolean configured = StringUtils.hasText(baseUrl);
        boolean valid = TrackingLinkPolicy.isAcceptableBaseUrl(baseUrl);
        boolean https = false;
        boolean loopback = false;
        if (configured) {
            try {
                URI uri = new URI(baseUrl.trim());
                https = "https".equalsIgnoreCase(uri.getScheme());
                loopback = isLoopbackHost(uri.getHost());
            } catch (Exception ignored) {
                // Ungueltige URL -> https/loopback bleiben false; valid ist ohnehin false.
            }
        }
        return new SystemStatus.TrackingStatus(configured, baseUrl, valid, https, loopback);
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]");
    }

    private SystemStatus.FileStorageStatus buildFileStorage() {
        Path baseDir = fileStorageService.baseDir();
        boolean exists = Files.isDirectory(baseDir);
        boolean writable = exists && Files.isWritable(baseDir);
        return new SystemStatus.FileStorageStatus(baseDir.toString(), exists, writable,
                fileStorageProperties.getMaxFileSizeBytes());
    }

    private SystemStatus.DatabaseStatus buildDatabase() {
        // Live-Erreichbarkeit: DataSourceUtils nutzt die transaktionsgebundene Verbindung, falls eine
        // Transaktion laeuft (kein zweiter Pool-Zugriff), sonst eine regulaere Pool-Verbindung.
        boolean reachable = false;
        Connection connection = null;
        try {
            connection = DataSourceUtils.getConnection(dataSource);
            reachable = connection.isValid(2);
        } catch (Exception e) {
            log.warn("Datenbank-Statuspruefung fehlgeschlagen (Kategorie=CONNECT).");
        } finally {
            if (connection != null) {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
        return new SystemStatus.DatabaseStatus(reachable, schemaVersion, expectedVersion, schemaUpToDate);
    }

    /**
     * Hoechste bekannte (aufgeloeste) Migrationsversion; faellt auf die aktuelle Version zurueck. Es wird
     * bewusst das Maximum ueber {@link MigrationVersion#compareTo} gebildet (nicht auf die Reihenfolge von
     * {@code info.all()} vertraut).
     */
    private static String latestResolvedVersion(MigrationInfoService info, String fallback) {
        MigrationVersion max = null;
        for (MigrationInfo mi : info.all()) {
            MigrationVersion version = mi.getVersion();
            if (version != null && (max == null || version.compareTo(max) > 0)) {
                max = version;
            }
        }
        return max != null ? max.getVersion() : fallback;
    }

    private SystemStatus.OverallReadiness buildReadiness(SystemStatus.AdminStatus admin,
                                                         SystemStatus.SmtpStatus smtp,
                                                         SystemStatus.SenderStatus sender,
                                                         SystemStatus.TrackingStatus tracking,
                                                         SystemStatus.FileStorageStatus fileStorage,
                                                         SystemStatus.DatabaseStatus database) {
        boolean readyForLocalUse = admin.configured() && database.reachable()
                && fileStorage.exists() && fileStorage.writable();
        boolean readyForPreview = readyForLocalUse && sender.configured();
        boolean readyForRealSmtp = smtp.hostConfigured() && smtp.portValid()
                && (!smtp.authEnabled() || smtp.usernamePresent())
                && (!smtp.authEnabled() || smtp.passwordPresent())
                && sender.configured() && sender.allowedByAllowlist()
                && smtp.liveSendEnabled();
        boolean readyForExternalTracking = tracking.valid() && tracking.https() && !tracking.loopback();
        return new SystemStatus.OverallReadiness(readyForLocalUse, readyForPreview, readyForRealSmtp,
                readyForExternalTracking);
    }
}
