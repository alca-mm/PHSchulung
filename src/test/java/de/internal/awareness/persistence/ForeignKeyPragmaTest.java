package de.internal.awareness.persistence;

import de.internal.awareness.support.SqliteFlywayJpaTest;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.sqlite.SQLiteConnection;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code PRAGMA foreign_keys} ist auf der Verbindung aktiv, die ein normaler JPA-Persistenztest
 * (und damit die Anwendung innerhalb einer Transaktion) tatsaechlich verwendet.
 *
 * <p>Abgedeckte Faelle:</p>
 * <ol>
 *   <li>Die transaktionsgebundene JDBC-Verbindung ({@link DataSourceUtils}) meldet
 *       {@code foreign_keys = 1}.</li>
 *   <li>Die Verbindung der Hibernate-Session meldet {@code foreign_keys = 1} und ist physisch
 *       dieselbe wie die transaktionsgebundene JDBC-Verbindung (rohes JDBC in den FK-Tests laeuft
 *       also in derselben, zurueckgerollten Testtransaktion).</li>
 * </ol>
 * Alle physischen Pool-Verbindungen prueft {@link ForeignKeyPragmaPooledConnectionsTest}.
 */
@SqliteFlywayJpaTest
class ForeignKeyPragmaTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void foreignKeysAreEnabledOnTransactionBoundJdbcConnection() throws SQLException {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();

        Connection con = DataSourceUtils.getConnection(dataSource);
        try {
            assertThat(DataSourceUtils.isConnectionTransactional(con, dataSource)).isTrue();
            assertThat(foreignKeysPragma(con))
                    .as("PRAGMA foreign_keys auf der transaktionsgebundenen Verbindung")
                    .isEqualTo(1);
        } finally {
            DataSourceUtils.releaseConnection(con, dataSource);
        }
    }

    @Test
    void foreignKeysAreEnabledOnConnectionUsedByHibernateSession() throws SQLException {
        Session session = entityManager.getEntityManager().unwrap(Session.class);

        int pragmaInSession = session.doReturningWork(ForeignKeyPragmaTest::foreignKeysPragma);
        SQLiteConnection sessionPhysical =
                session.doReturningWork(con -> con.unwrap(SQLiteConnection.class));

        assertThat(pragmaInSession)
                .as("PRAGMA foreign_keys auf der Verbindung der Hibernate-Session")
                .isEqualTo(1);

        Connection con = DataSourceUtils.getConnection(dataSource);
        try {
            assertThat(con.unwrap(SQLiteConnection.class))
                    .as("Hibernate-Session und DataSourceUtils nutzen dieselbe physische Verbindung")
                    .isSameAs(sessionPhysical);
        } finally {
            DataSourceUtils.releaseConnection(con, dataSource);
        }
    }

    static int foreignKeysPragma(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("PRAGMA foreign_keys");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }
}
