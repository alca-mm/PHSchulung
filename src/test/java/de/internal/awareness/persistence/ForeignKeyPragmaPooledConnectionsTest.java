package de.internal.awareness.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.sqlite.SQLiteConnection;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code PRAGMA foreign_keys} ist auf JEDER physischen Pool-Verbindung aktiv, nicht nur auf der
 * zufaellig zuerst erzeugten.
 *
 * <p>Abgedeckter Fall: Im vollstaendigen Anwendungskontext (ohne Webserver) mit einem Pool von drei
 * Verbindungen werden drei Verbindungen GLEICHZEITIG ausgeliehen (nicht-transaktionaler Test, daher
 * keine Transaktionsbindung). Es muessen drei verschiedene physische SQLite-Verbindungen sein, und
 * jede meldet {@code foreign_keys = 1}. Damit ist abgesichert, dass die Aktivierung pro Verbindung
 * erfolgt (z. B. Hikari connection-init-sql) und auch Verbindungen nach der Flyway-Migration
 * betrifft.</p>
 *
 * <p>Isolation: eigener Spring-Kontext (abweichende Pool-Groesse) mit eigener Test-DB unter
 * target/; die Dev-DB unter ./data/ wird nicht beruehrt. Es werden nur Pragmas gelesen.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.minimum-idle=3"
})
class ForeignKeyPragmaPooledConnectionsTest {

    private static final int POOL_SIZE = 3;

    @Autowired
    private DataSource dataSource;

    @Test
    void everyPhysicalPooledConnectionHasForeignKeysEnabled() throws SQLException {
        assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize())
                .as("Test setzt einen Pool mit %d Verbindungen voraus", POOL_SIZE)
                .isEqualTo(POOL_SIZE);

        List<Connection> borrowed = new ArrayList<>();
        try {
            // Alle Verbindungen gleichzeitig halten -> der Pool muss jede physische Verbindung liefern.
            for (int i = 0; i < POOL_SIZE; i++) {
                borrowed.add(dataSource.getConnection());
            }

            Set<SQLiteConnection> physical = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int i = 0; i < borrowed.size(); i++) {
                Connection con = borrowed.get(i);
                physical.add(con.unwrap(SQLiteConnection.class));
                assertThat(ForeignKeyPragmaTest.foreignKeysPragma(con))
                        .as("PRAGMA foreign_keys auf Pool-Verbindung %d", i + 1)
                        .isEqualTo(1);
            }
            assertThat(physical)
                    .as("Anzahl verschiedener physischer SQLite-Verbindungen")
                    .hasSize(POOL_SIZE);
        } finally {
            for (Connection con : borrowed) {
                con.close();
            }
        }
    }
}
