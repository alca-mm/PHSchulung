package de.internal.awareness.system;

import de.internal.awareness.config.AppAdminProperties;
import de.internal.awareness.config.AppMailProperties;
import de.internal.awareness.config.AppTrackingProperties;
import de.internal.awareness.config.FileStorageProperties;
import de.internal.awareness.file.FileStorageService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Readiness-Berechnung und Statusermittlung von {@link SystemStatusService} (ohne echte Infrastruktur:
 * Flyway/DataSource/FileStorageService sind gemockt). Prueft die vier Bereitschaftsstufen, den Datei- und
 * DB-/Flyway-Status. Secrets sind durch die DTO-Struktur bereits ausgeschlossen (nur Booleans/sichere Werte).
 */
class SystemStatusServiceTest {

    @TempDir
    Path existingDir;

    private SystemStatusService service(String adminUser, String adminPass,
                                        String host, int port, String smtpUser, String smtpPass,
                                        boolean auth, boolean starttls,
                                        String defaultSender, List<String> allowedSenders,
                                        List<String> recipientDomains, boolean liveSend,
                                        String trackingBaseUrl,
                                        boolean dirExists, boolean dbReachable,
                                        String flywayVersion, boolean pending) {
        AppAdminProperties admin = new AppAdminProperties();
        admin.setUsername(adminUser);
        admin.setPassword(adminPass);
        AppMailProperties mail = new AppMailProperties();
        mail.setLiveSendEnabled(liveSend);
        mail.setAllowedSenders(allowedSenders);
        mail.setAllowedRecipientDomains(recipientDomains);
        mail.setDefaultSender(defaultSender);
        mail.setDefaultSenderName("IT Security");
        AppTrackingProperties tracking = new AppTrackingProperties();
        tracking.setBaseUrl(trackingBaseUrl);
        FileStorageProperties fsp = new FileStorageProperties();
        fsp.setMaxFileSizeBytes(5_242_880L);

        FileStorageService fs = mock(FileStorageService.class);
        Path dir = dirExists ? existingDir : existingDir.resolve("missing-" + UUID.randomUUID());
        when(fs.baseDir()).thenReturn(dir);

        return new SystemStatusService(admin, mail, tracking, fsp, fs, mockFlyway(flywayVersion, pending),
                mockDataSource(dbReachable), host, port, smtpUser, smtpPass, auth, starttls);
    }

    private static Flyway mockFlyway(String version, boolean pending) {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        MigrationInfo current = mock(MigrationInfo.class);
        when(current.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        when(info.current()).thenReturn(current);
        MigrationInfo all = mock(MigrationInfo.class);
        when(all.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        when(info.all()).thenReturn(new MigrationInfo[]{all});
        when(info.pending()).thenReturn(pending ? new MigrationInfo[]{mock(MigrationInfo.class)} : new MigrationInfo[0]);
        return flyway;
    }

    private static DataSource mockDataSource(boolean reachable) {
        DataSource ds = mock(DataSource.class);
        try {
            if (reachable) {
                Connection connection = mock(Connection.class);
                when(connection.isValid(anyInt())).thenReturn(true);
                when(ds.getConnection()).thenReturn(connection);
            } else {
                when(ds.getConnection()).thenThrow(new SQLException("unreachable"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return ds;
    }

    /** Eine vollstaendig versandbereite Konfiguration. */
    private SystemStatusService fullyReady() {
        return service("admin", "pw", "smtp.example.invalid", 587, "smtp-user", "smtp-pass", true, true,
                "training@example.invalid", List.of("training@example.invalid"), List.of(), true,
                "https://training.example.invalid", true, true, "9", false);
    }

    // 15
    @Test
    void fullConfigurationIsReadyForRealSmtp() {
        SystemStatus status = fullyReady().build("admin");
        assertThat(status.readiness().readyForRealSmtp()).isTrue();
        assertThat(status.readiness().readyForLocalUse()).isTrue();
        assertThat(status.readiness().readyForPreview()).isTrue();
    }

    // 12
    @Test
    void missingSmtpHostIsNotReadyForRealSmtp() {
        SystemStatus status = service("admin", "pw", "", 587, "u", "p", true, true,
                "training@example.invalid", List.of("training@example.invalid"), List.of(), true,
                "https://training.example.invalid", true, true, "9", false).build("admin");
        assertThat(status.smtp().hostConfigured()).isFalse();
        assertThat(status.readiness().readyForRealSmtp()).isFalse();
    }

    // 13
    @Test
    void liveSendDisabledIsNotReadyForRealSmtp() {
        SystemStatus status = service("admin", "pw", "smtp.example.invalid", 587, "u", "p", true, true,
                "training@example.invalid", List.of("training@example.invalid"), List.of(), false,
                "https://training.example.invalid", true, true, "9", false).build("admin");
        assertThat(status.readiness().readyForRealSmtp()).isFalse();
    }

    // 14
    @Test
    void senderNotAllowedIsNotReadyForRealSmtp() {
        SystemStatus status = service("admin", "pw", "smtp.example.invalid", 587, "u", "p", true, true,
                "training@example.invalid", List.of("someone-else@example.invalid"), List.of(), true,
                "https://training.example.invalid", true, true, "9", false).build("admin");
        assertThat(status.sender().allowedByAllowlist()).isFalse();
        assertThat(status.readiness().readyForRealSmtp()).isFalse();
    }

    // 16
    @Test
    void localhostTrackingIsLocalButNotExternallyReady() {
        SystemStatus status = service("admin", "pw", "smtp.example.invalid", 587, "u", "p", true, true,
                "training@example.invalid", List.of("training@example.invalid"), List.of(), true,
                "http://localhost:8080", true, true, "9", false).build("admin");
        assertThat(status.tracking().valid()).isTrue();
        assertThat(status.tracking().loopback()).isTrue();
        assertThat(status.tracking().https()).isFalse();
        assertThat(status.readiness().readyForExternalTracking()).isFalse();
        assertThat(status.readiness().readyForLocalUse()).isTrue();
    }

    // 17
    @Test
    void httpsTrackingIsReadyForExternalTracking() {
        SystemStatus status = fullyReady().build("admin");
        assertThat(status.tracking().https()).isTrue();
        assertThat(status.tracking().loopback()).isFalse();
        assertThat(status.readiness().readyForExternalTracking()).isTrue();
    }

    // 18
    @Test
    void invalidTrackingUrlIsNotReady() {
        SystemStatus status = service("admin", "pw", "smtp.example.invalid", 587, "u", "p", true, true,
                "training@example.invalid", List.of("training@example.invalid"), List.of(), true,
                "javascript:alert(1)", true, true, "9", false).build("admin");
        assertThat(status.tracking().valid()).isFalse();
        assertThat(status.readiness().readyForExternalTracking()).isFalse();
    }

    // 8 + 9
    @Test
    void databaseAndFlywayStatusAreReported() {
        SystemStatus status = fullyReady().build("admin");
        assertThat(status.database().reachable()).isTrue();
        assertThat(status.database().currentVersion()).isEqualTo("9");
        assertThat(status.database().expectedVersion()).isEqualTo("9");
        assertThat(status.database().upToDate()).isTrue();
    }

    @Test
    void unreachableDatabaseIsReported() {
        SystemStatus status = service("admin", "pw", "smtp.example.invalid", 587, "u", "p", true, true,
                "training@example.invalid", List.of("training@example.invalid"), List.of(), true,
                "https://training.example.invalid", true, false, "9", false).build("admin");
        assertThat(status.database().reachable()).isFalse();
        assertThat(status.readiness().readyForLocalUse()).isFalse();
    }

    @Test
    void pendingMigrationsAreNotUpToDate() {
        SystemStatus status = service("admin", "pw", "smtp.example.invalid", 587, "u", "p", true, true,
                "training@example.invalid", List.of("training@example.invalid"), List.of(), true,
                "https://training.example.invalid", true, true, "8", true).build("admin");
        assertThat(status.database().upToDate()).isFalse();
    }

    // 10
    @Test
    void fileStorageStatusReflectsExistingWritableDirectory() {
        SystemStatus status = fullyReady().build("admin");
        assertThat(status.fileStorage().exists()).isTrue();
        assertThat(status.fileStorage().writable()).isTrue();
        assertThat(status.fileStorage().maxFileSizeBytes()).isEqualTo(5_242_880L);
    }

    // 11
    @Test
    void missingDirectoryIsReportedAsNotExisting() {
        SystemStatus status = service("admin", "pw", "smtp.example.invalid", 587, "u", "p", true, true,
                "training@example.invalid", List.of("training@example.invalid"), List.of(), true,
                "https://training.example.invalid", false, true, "9", false).build("admin");
        assertThat(status.fileStorage().exists()).isFalse();
        assertThat(status.fileStorage().writable()).isFalse();
        assertThat(status.readiness().readyForLocalUse()).isFalse();
    }

    @Test
    void recipientAllowlistSemanticsAreReported() {
        SystemStatus empty = fullyReady().build("admin");
        assertThat(empty.recipientAllowlist().active()).isFalse();

        SystemStatus withDomains = service("admin", "pw", "smtp.example.invalid", 587, "u", "p", true, true,
                "training@example.invalid", List.of("training@example.invalid"), List.of("firma.example"), true,
                "https://training.example.invalid", true, true, "9", false).build("admin");
        assertThat(withDomains.recipientAllowlist().active()).isTrue();
        assertThat(withDomains.recipientAllowlist().domains()).containsExactly("firma.example");
    }

    @Test
    void adminConfiguredReflectsCredentialsAndCurrentUser() {
        SystemStatus status = fullyReady().build("admin");
        assertThat(status.admin().configured()).isTrue();
        assertThat(status.admin().currentUser()).isEqualTo("admin");
    }
}
