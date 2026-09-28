package de.internal.awareness.persistence;

import com.zaxxer.hikari.HikariDataSource;
import de.internal.awareness.support.SqliteFlywayJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fall 8: Spring-Tests arbeiten ausschliesslich auf einer isolierten Test-DB unter
 * target/pat-test-*.db und NIE auf der Entwicklungs-DB ./data/app.db - auch wenn die Tests die
 * Hauptkonfiguration (application.properties) erben.
 */
@SqliteFlywayJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TestDatabaseIsolationGuardTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void configuredJdbcUrlPointsToIsolatedTestDatabase() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        String jdbcUrl = ((HikariDataSource) dataSource).getJdbcUrl().replace('\\', '/');

        assertThat(jdbcUrl)
                .startsWith("jdbc:sqlite:")
                .contains("target/pat-test-")
                .doesNotContain("data/app.db");
    }

    @Test
    void openedDatabaseFileLiesInTargetAndIsNotTheDevelopmentDatabase() throws IOException {
        // Tatsaechlich geoeffnete Hauptdatenbank (absoluter Pfad) statt nur der konfigurierten URL.
        String file = new JdbcTemplate(dataSource).queryForObject(
                "SELECT file FROM pragma_database_list WHERE name = 'main'", String.class);
        assertThat(file).isNotBlank();

        // toRealPath gleicht u. a. Windows-Kurznamen (8.3) und Gross-/Kleinschreibung an.
        Path dbFile = Path.of(file).toRealPath();
        Path targetDir = Path.of("target").toRealPath();
        Path devDb = Path.of("data", "app.db");

        assertThat(dbFile.getParent()).isEqualTo(targetDir);
        assertThat(dbFile.getFileName().toString()).startsWith("pat-test-").endsWith(".db");
        assertThat(Files.exists(devDb) && Files.isSameFile(dbFile, devDb))
                .as("Test-DB darf nicht ./data/app.db sein")
                .isFalse();
    }
}
