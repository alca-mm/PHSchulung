package de.internal.awareness.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.internal.awareness.file.GeneratedFileType;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteConnection;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway V10 ({@code V10__extend_generated_file_types.sql}): Tabellen-Neuaufbau von {@code generated_file},
 * um die file_type-CHECK-Constraint auf alle {@link GeneratedFileType}-Konstanten zu erweitern.
 *
 * <p>Ohne Spring-Kontext gegen isolierte SQLite-Dateien in einem {@link TempDir} (./data/app.db wird nie
 * beruehrt). Migriert wird - wie in der Anwendung - ueber einen Hikari-Pool, dessen Verbindungen per
 * {@code connection-init-sql} {@code PRAGMA foreign_keys = ON} setzen. Nur so ist nachweisbar, dass V10 die
 * Foreign Keys waehrend des Neuaufbaus wirklich abschaltet (sonst wuerde {@code DROP TABLE generated_file}
 * an der RESTRICT-Referenz aus mail_batch scheitern) und die Verbindung danach wieder mit
 * {@code foreign_keys = 1} zurueckgibt.</p>
 * <ul>
 *   <li>V9 -&gt; V10 mit vorhandenen Daten: generated_file- und mail_batch-Zeilen bleiben exakt erhalten
 *       (Wert UND Speicherklasse), der FK von mail_batch zeigt weiterhin auf generated_file, kein
 *       Zwischenobjekt generated_file__new bleibt zurueck, foreign_key_check leer, integrity_check ok.</li>
 *   <li>Nach dem Neuaufbau: FK-Durchsetzung (unbekannte generated_file_id, RESTRICT beim Loeschen/Aendern
 *       einer referenzierten Datei) funktioniert weiter.</li>
 *   <li>Unique-Index uk_generated_file_stored_filename existiert mit identischem Namen und wirkt.</li>
 *   <li>Spaltenlayout, Indizes und alle uebrigen Schemaobjekte sind vor und nach V10 identisch; die
 *       Tabellen-DDL unterscheidet sich ausschliesslich in der Werteliste der CHECK-Constraint.</li>
 *   <li>Alle GeneratedFileType-Konstanten sind einfuegbar, andere Werte (makrofaehig, ausfuehrbar,
 *       kleingeschrieben, leer) lehnt die CHECK-Constraint ab; die CHECK-Werteliste entspricht exakt der
 *       Enum-Konstantenmenge (Drift-Schutz).</li>
 * </ul>
 * Tests, die den Uebergang V9 -&gt; V10 beschreiben, migrieren bewusst bis Zielversion "10" (stabil gegenueber
 * kuenftigen Migrationen); die Enum-bezogenen Tests migrieren auf den neuesten Stand.
 */
class GeneratedFileTypesMigrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final String VERSION_BEFORE = "9";
    private static final String VERSION_REBUILD = "10";

    private static final String V5_CHECK = "CHECK (file_type IN ('DOCX', 'XML'))";
    private static final String V10_CHECK =
            "CHECK (file_type IN ('DOCX', 'XML', 'PDF', 'XLSX', 'PPTX', 'TXT', 'CSV'))";

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------------------------------------
    // Hilfsmethoden
    // ------------------------------------------------------------------------------------------------

    private static String jdbcUrl(Path dbFile) {
        return "jdbc:sqlite:" + dbFile.toAbsolutePath();
    }

    /** Anwendungsnaher Pool: eine physische Verbindung, foreign_keys = ON per connection-init-sql. */
    private static HikariDataSource appLikePool(Path dbFile) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl(dbFile));
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionInitSql("PRAGMA foreign_keys = ON");
        config.setPoolName("v10-migration-test");
        return new HikariDataSource(config);
    }

    /** Flyway mit Standardkonfiguration (baselineOnMigrate=false) ueber den anwendungsnahen Pool. */
    private static MigrateResult migrate(Path dbFile, String targetVersion) {
        try (HikariDataSource pool = appLikePool(dbFile)) {
            return flyway(pool, targetVersion).migrate();
        }
    }

    private static Flyway flyway(HikariDataSource pool, String targetVersion) {
        return Flyway.configure()
                .dataSource(pool)
                .locations(MIGRATION_LOCATION)
                .target(targetVersion)
                .load();
    }

    /** Neue, eigene Verbindung mit aktivierten Foreign Keys (wie jede Pool-Verbindung der Anwendung). */
    private static Connection openWithForeignKeys(Path dbFile) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl(dbFile));
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        assertThat(foreignKeysPragma(connection)).as("PRAGMA foreign_keys auf der Testverbindung").isEqualTo(1);
        return connection;
    }

    private static int foreignKeysPragma(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA foreign_keys")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static List<String> queryStrings(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    row.add(rs.getString(i));
                }
                values.add(String.join("|", row));
            }
        }
        return values;
    }

    private static List<String> queryStrings(Path dbFile, String sql) throws SQLException {
        try (Connection connection = openWithForeignKeys(dbFile)) {
            return queryStrings(connection, sql);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Exakter Zeilen-Schnappschuss einer Tabelle: je Zeile alle Spalten ueber SQLite {@code quote()}, das
     * Wert UND Speicherklasse abbildet (Text in Hochkommas, Ganzzahl ohne, NULL als NULL).
     */
    private static List<String> rowSnapshot(Path dbFile, String table) throws SQLException {
        try (Connection connection = openWithForeignKeys(dbFile)) {
            List<String> columns = queryStrings(connection,
                    "SELECT name FROM pragma_table_info('" + table + "') ORDER BY cid");
            assertThat(columns).as("Spalten von %s", table).isNotEmpty();
            String expression = columns.stream()
                    .map(column -> "quote(\"" + column + "\")")
                    .collect(Collectors.joining(" || '|' || "));
            return queryStrings(connection, "SELECT " + expression + " FROM " + table + " ORDER BY id");
        }
    }

    /** Spaltenlayout laut {@code PRAGMA table_info}: cid, name, type, notnull, dflt_value, pk. */
    private static List<String> columnLayout(Path dbFile, String table) throws SQLException {
        return queryStrings(dbFile, "SELECT cid, name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('"
                + table + "') ORDER BY cid");
    }

    private static List<String> indexList(Path dbFile, String table) throws SQLException {
        return queryStrings(dbFile, "SELECT name, \"unique\", origin, partial FROM pragma_index_list('"
                + table + "') ORDER BY name");
    }

    private static List<String> foreignKeyList(Path dbFile, String table) throws SQLException {
        return queryStrings(dbFile, "SELECT \"table\", \"from\", \"to\", on_update, on_delete, \"match\" "
                + "FROM pragma_foreign_key_list('" + table + "') ORDER BY id, seq");
    }

    private static String tableSql(Path dbFile, String table) throws SQLException {
        List<String> sql = queryStrings(dbFile,
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'");
        assertThat(sql).as("DDL von %s", table).hasSize(1);
        return sql.getFirst();
    }

    /**
     * Alle Schemaobjekte ausser der neu aufgebauten Tabelle generated_file und flyway_schema_history
     * (type, name, tbl_name, sql - ohne rootpage, die sich beim Neuaufbau aendern darf).
     */
    private static List<String> otherSchemaObjects(Path dbFile) throws SQLException {
        return queryStrings(dbFile, "SELECT type, name, tbl_name, sql FROM sqlite_master "
                + "WHERE NOT (type = 'table' AND name = 'generated_file') "
                + "AND tbl_name <> 'flyway_schema_history' ORDER BY type, name");
    }

    /**
     * Liefert die in {@code CHECK (file_type IN ('A', 'B', ...))} erlaubten Werte der Tabellen-DDL und prueft,
     * dass es genau eine solche Constraint gibt (gleiches Muster wie EntitySchemaAlignmentTest).
     */
    private static Set<String> fileTypeCheckValues(String tableSql) {
        Pattern check = Pattern.compile("CHECK\\s*\\(\\s*\\(?\\s*\"?file_type\"?\\s+IN\\s*\\(([^)]*)\\)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = check.matcher(tableSql);
        Set<String> values = new TreeSet<>();
        int matches = 0;
        while (matcher.find()) {
            matches++;
            Matcher literal = Pattern.compile("'([^']*)'").matcher(matcher.group(1));
            while (literal.find()) {
                values.add(literal.group(1));
            }
        }
        assertThat(matches).as("Anzahl CHECK-IN-Constraints fuer generated_file.file_type").isEqualTo(1);
        return values;
    }

    private static Set<String> generatedFileTypeNames() {
        return Arrays.stream(GeneratedFileType.values())
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static void insertGeneratedFile(Connection connection, Long id, String storedFilename, String fileType)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO generated_file (id, display_name, stored_filename, download_filename, file_type, "
                        + "content_type, file_size, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            if (id == null) {
                ps.setNull(1, java.sql.Types.INTEGER);
            } else {
                ps.setLong(1, id);
            }
            ps.setString(2, "Trainingsdokument");
            ps.setString(3, storedFilename);
            ps.setString(4, "trainingsdokument.bin");
            ps.setString(5, fileType);
            ps.setString(6, "application/octet-stream");
            ps.setLong(7, 42L);
            ps.setString(8, "2026-09-25 10:15:30.123");
            ps.executeUpdate();
        }
    }

    /**
     * Migriert eine leere DB bis V9 und legt Bestandsdaten an: zwei Dateien (DOCX, XML), einen Versand mit
     * Anhang (FK auf Datei 1) und einen ohne Anhang (generated_file_id NULL). Eingefuegt wird mit aktivierten
     * Foreign Keys, die Bestandsdaten sind also referenziell gueltig.
     */
    private Path databaseAtV9WithData(String name) throws SQLException {
        Path dbFile = tempDir.resolve(name);
        MigrateResult toV9 = migrate(dbFile, VERSION_BEFORE);
        assertThat(toV9.success).isTrue();
        assertThat(toV9.targetSchemaVersion).isEqualTo(VERSION_BEFORE);

        try (Connection connection = openWithForeignKeys(dbFile)) {
            execute(connection, "INSERT INTO generated_file (id, display_name, stored_filename, download_filename, "
                    + "file_type, content_type, file_size, created_at) VALUES (1, 'Rechnung September', "
                    + "'0f8fad5b-d9cb-469f-a165-70867728950e.docx', 'rechnung-september.docx', 'DOCX', "
                    + "'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 12345, "
                    + "'2026-09-25 10:15:30.123')");
            execute(connection, "INSERT INTO generated_file (id, display_name, stored_filename, download_filename, "
                    + "file_type, content_type, file_size, created_at) VALUES (2, 'Lieferschein', "
                    + "'7c9e6679-7425-40de-944b-e07fc1f90ae7.xml', 'lieferschein.xml', 'XML', 'application/xml', "
                    + "2048, 1758795330123)");
            execute(connection, "INSERT INTO mail_batch (id, subject, body, sender_email, sender_name, "
                    + "generated_file_id, attachment_filename, recipient_count, created_at) VALUES (10, "
                    + "'Ihre Rechnung', 'Bitte pruefen Sie den Anhang.', 'awareness@example.invalid', "
                    + "'Awareness-Team', 1, 'rechnung-september.docx', 3, '2026-09-25 10:20:00.000')");
            execute(connection, "INSERT INTO mail_batch (id, subject, body, sender_email, sender_name, "
                    + "generated_file_id, attachment_filename, recipient_count, created_at) VALUES (11, "
                    + "'Hinweis', 'Ohne Anhang.', 'awareness@example.invalid', NULL, NULL, NULL, 1, "
                    + "'2026-09-25 10:25:00.000')");
        }
        return dbFile;
    }

    // ------------------------------------------------------------------------------------------------
    // V9 -> V10: Datenerhalt und Foreign Keys
    // ------------------------------------------------------------------------------------------------

    @Test
    void rebuildPreservesExistingRowsAndKeepsMailBatchForeignKeyOnGeneratedFile() throws SQLException {
        Path dbFile = databaseAtV9WithData("preserve.db");
        List<String> filesBefore = rowSnapshot(dbFile, "generated_file");
        List<String> batchesBefore = rowSnapshot(dbFile, "mail_batch");
        assertThat(filesBefore).hasSize(2);
        assertThat(batchesBefore).hasSize(2);

        // Die Verbindung hat foreign_keys = ON: ohne PRAGMA foreign_keys = OFF in V10 scheiterte
        // DROP TABLE generated_file an der RESTRICT-Referenz aus mail_batch.
        MigrateResult result = migrate(dbFile, VERSION_REBUILD);

        assertThat(result.success).isTrue();
        assertThat(result.initialSchemaVersion).isEqualTo(VERSION_BEFORE);
        assertThat(result.targetSchemaVersion).isEqualTo(VERSION_REBUILD);
        assertThat(result.migrationsExecuted).isEqualTo(1);

        // Werte UND Speicherklassen unveraendert (quote()-Schnappschuss), inkl. Ids.
        assertThat(rowSnapshot(dbFile, "generated_file")).containsExactlyElementsOf(filesBefore);
        assertThat(rowSnapshot(dbFile, "mail_batch")).containsExactlyElementsOf(batchesBefore);

        // FK von mail_batch zeigt weiterhin (textuell und laut PRAGMA) auf generated_file.
        String mailBatchSql = tableSql(dbFile, "mail_batch");
        assertThat(mailBatchSql)
                .contains("REFERENCES generated_file (id) ON DELETE RESTRICT ON UPDATE RESTRICT")
                .doesNotContain("generated_file__new");
        assertThat(foreignKeyList(dbFile, "mail_batch"))
                .containsExactly("generated_file|generated_file_id|id|RESTRICT|RESTRICT|NONE");

        // Kein Zwischenobjekt bleibt zurueck, auch nicht als Verweis in anderer DDL.
        assertThat(queryStrings(dbFile, "SELECT name FROM sqlite_master WHERE name LIKE '%\\_\\_new%' ESCAPE '\\' "
                + "OR tbl_name LIKE '%\\_\\_new%' ESCAPE '\\' OR sql LIKE '%generated_file\\_\\_new%' ESCAPE '\\'"))
                .isEmpty();

        assertThat(queryStrings(dbFile, "PRAGMA foreign_key_check")).isEmpty();
        assertThat(queryStrings(dbFile, "PRAGMA integrity_check")).containsExactly("ok");
    }

    @Test
    void foreignKeysAreStillEnforcedAfterRebuild() throws SQLException {
        Path dbFile = databaseAtV9WithData("fk-enforced.db");
        assertThat(migrate(dbFile, VERSION_REBUILD).success).isTrue();

        try (Connection connection = openWithForeignKeys(dbFile)) {
            // Kind-Insert mit unbekannter Datei-Id wird abgelehnt.
            assertThatThrownBy(() -> execute(connection, "INSERT INTO mail_batch (subject, body, sender_email, "
                    + "generated_file_id, recipient_count, created_at) VALUES ('s', 'b', 'awareness@example.invalid', "
                    + "999, 1, '2026-09-25 11:00:00.000')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("FOREIGN KEY constraint failed");

            // Referenzierte Datei kann weder geloescht noch in der Id geaendert werden (RESTRICT).
            assertThatThrownBy(() -> execute(connection, "DELETE FROM generated_file WHERE id = 1"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("FOREIGN KEY constraint failed");
            assertThatThrownBy(() -> execute(connection, "UPDATE generated_file SET id = 100 WHERE id = 1"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("FOREIGN KEY constraint failed");
            assertThat(queryStrings(connection, "SELECT COUNT(*) FROM generated_file WHERE id = 1"))
                    .containsExactly("1");

            // Gegenprobe: nicht referenzierte Datei ist loeschbar, gueltiger Kind-Insert klappt.
            assertThatCode(() -> execute(connection, "INSERT INTO mail_batch (subject, body, sender_email, "
                    + "generated_file_id, recipient_count, created_at) VALUES ('s', 'b', 'awareness@example.invalid', "
                    + "1, 1, '2026-09-25 11:00:00.000')")).doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "DELETE FROM generated_file WHERE id = 2"))
                    .doesNotThrowAnyException();
        }
        assertThat(queryStrings(dbFile, "PRAGMA foreign_key_check")).isEmpty();
    }

    @Test
    void migrationReturnsPooledConnectionWithForeignKeysEnabled() throws SQLException {
        Path dbFile = databaseAtV9WithData("pool-state.db");

        try (HikariDataSource pool = appLikePool(dbFile)) {
            SQLiteConnection physicalBefore;
            try (Connection connection = pool.getConnection()) {
                physicalBefore = connection.unwrap(SQLiteConnection.class);
            }

            assertThat(flyway(pool, VERSION_REBUILD).migrate().success).isTrue();

            // Pool mit genau einer physischen Verbindung: Flyway hat dieselbe Verbindung benutzt. V10 schaltet
            // foreign_keys voruebergehend OFF - die Verbindung darf den Zustand nicht behalten.
            try (Connection connection = pool.getConnection()) {
                assertThat(connection.unwrap(SQLiteConnection.class))
                        .as("dieselbe physische Verbindung wie vor der Migration")
                        .isSameAs(physicalBefore);
                assertThat(foreignKeysPragma(connection))
                        .as("PRAGMA foreign_keys auf der von Flyway benutzten Pool-Verbindung")
                        .isEqualTo(1);
                assertThat(connection.getAutoCommit()).isTrue();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // V9 -> V10: Struktur (Spalten, Index, uebrige Schemaobjekte)
    // ------------------------------------------------------------------------------------------------

    @Test
    void columnLayoutIndexesAndAllOtherSchemaObjectsAreIdenticalBeforeAndAfterRebuild() throws SQLException {
        Path dbFile = databaseAtV9WithData("layout.db");
        List<String> columnsBefore = columnLayout(dbFile, "generated_file");
        List<String> indexesBefore = indexList(dbFile, "generated_file");
        List<String> foreignKeysBefore = foreignKeyList(dbFile, "generated_file");
        List<String> otherObjectsBefore = otherSchemaObjects(dbFile);
        String tableSqlBefore = tableSql(dbFile, "generated_file");
        assertThat(tableSqlBefore).contains(V5_CHECK);

        assertThat(migrate(dbFile, VERSION_REBUILD).success).isTrue();

        assertThat(columnLayout(dbFile, "generated_file")).containsExactlyElementsOf(columnsBefore);
        // SQLite meldet den Typ der rowid-Alias-Spalte (INTEGER PRIMARY KEY) in Grossbuchstaben.
        assertThat(columnLayout(dbFile, "generated_file")).containsExactly(
                "0|id|INTEGER|0|null|1",
                "1|display_name|varchar(255)|1|null|0",
                "2|stored_filename|varchar(255)|1|null|0",
                "3|download_filename|varchar(255)|1|null|0",
                "4|file_type|varchar(32)|1|null|0",
                "5|content_type|varchar(255)|1|null|0",
                "6|file_size|bigint|1|null|0",
                "7|created_at|timestamp|1|null|0");
        assertThat(indexList(dbFile, "generated_file")).containsExactlyElementsOf(indexesBefore);
        assertThat(foreignKeyList(dbFile, "generated_file")).isEmpty();
        assertThat(foreignKeysBefore).isEmpty();

        // Alle anderen Tabellen/Indizes (inkl. mail_batch mit seinem FK und der neu angelegte Unique-Index)
        // sind textuell unveraendert - der RENAME hat keine fremde DDL umgeschrieben.
        assertThat(otherSchemaObjects(dbFile)).containsExactlyElementsOf(otherObjectsBefore);

        // Die Tabellen-DDL unterscheidet sich ausschliesslich in der CHECK-Werteliste (SQLite setzt den
        // Tabellennamen nach RENAME in Anfuehrungszeichen).
        String tableSqlAfter = tableSql(dbFile, "generated_file");
        assertThat(tableSqlAfter).contains(V10_CHECK).startsWith("CREATE TABLE \"generated_file\"");
        assertThat(tableSqlAfter
                .replace("CREATE TABLE \"generated_file\"", "CREATE TABLE generated_file")
                .replace(V10_CHECK, V5_CHECK))
                .isEqualTo(tableSqlBefore);
    }

    @Test
    void uniqueStoredFilenameIndexIsRecreatedWithSameNameAndEnforced() throws SQLException {
        Path dbFile = databaseAtV9WithData("unique.db");
        assertThat(migrate(dbFile, VERSION_REBUILD).success).isTrue();

        assertThat(indexList(dbFile, "generated_file"))
                .containsExactly("uk_generated_file_stored_filename|1|c|0");
        assertThat(queryStrings(dbFile, "SELECT name FROM pragma_index_info('uk_generated_file_stored_filename')"))
                .containsExactly("stored_filename");
        assertThat(queryStrings(dbFile, "SELECT tbl_name, sql FROM sqlite_master "
                + "WHERE type = 'index' AND name = 'uk_generated_file_stored_filename'"))
                .containsExactly("generated_file|CREATE UNIQUE INDEX uk_generated_file_stored_filename "
                        + "ON generated_file (stored_filename)");

        try (Connection connection = openWithForeignKeys(dbFile)) {
            assertThatThrownBy(() -> insertGeneratedFile(connection, null,
                    "0f8fad5b-d9cb-469f-a165-70867728950e.docx", "DOCX"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("UNIQUE constraint failed: generated_file.stored_filename");
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Neuester Stand: CHECK-Constraint vs. GeneratedFileType
    // ------------------------------------------------------------------------------------------------

    @Test
    void allGeneratedFileTypesAreAcceptedByCheckConstraint() throws SQLException {
        Path dbFile = tempDir.resolve("all-types.db");
        assertThat(migrate(dbFile, "latest").success).isTrue();

        try (Connection connection = openWithForeignKeys(dbFile)) {
            for (GeneratedFileType type : GeneratedFileType.values()) {
                assertThatCode(() -> insertGeneratedFile(connection, null,
                        "stored-" + type.name() + "." + type.extension(), type.name()))
                        .as("Einfuegen von file_type %s", type.name())
                        .doesNotThrowAnyException();
            }
            assertThat(queryStrings(connection, "SELECT file_type FROM generated_file ORDER BY file_type"))
                    .containsExactlyInAnyOrderElementsOf(generatedFileTypeNames());
        }
        assertThat(GeneratedFileType.values()).hasSize(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXE", "DOCM", "XLSM", "PPTM", "DOTM", "docx", "pdf", "Csv", "", " PDF", "PDF "})
    void otherFileTypesAreRejectedByCheckConstraint(String invalidType) throws SQLException {
        Path dbFile = tempDir.resolve("invalid-type.db");
        assertThat(migrate(dbFile, "latest").success).isTrue();

        try (Connection connection = openWithForeignKeys(dbFile)) {
            assertThatThrownBy(() -> insertGeneratedFile(connection, null, "stored-invalid.bin", invalidType))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("CHECK constraint failed");
            assertThat(queryStrings(connection, "SELECT COUNT(*) FROM generated_file")).containsExactly("0");
        }
    }

    @Test
    void nullFileTypeIsRejected() throws SQLException {
        Path dbFile = tempDir.resolve("null-type.db");
        assertThat(migrate(dbFile, "latest").success).isTrue();

        try (Connection connection = openWithForeignKeys(dbFile)) {
            assertThatThrownBy(() -> insertGeneratedFile(connection, null, "stored-null.bin", null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("NOT NULL constraint failed: generated_file.file_type");
        }
    }

    @Test
    void checkConstraintValueSetEqualsExactlyTheGeneratedFileTypeConstants() throws SQLException {
        Path dbFile = tempDir.resolve("check-drift.db");
        assertThat(migrate(dbFile, "latest").success).isTrue();

        // Drift-Schutz: eine neue Enum-Konstante ohne Migration (oder umgekehrt) laesst diesen Test scheitern.
        assertThat(fileTypeCheckValues(tableSql(dbFile, "generated_file")))
                .isNotEmpty()
                .isEqualTo(generatedFileTypeNames())
                .containsExactlyInAnyOrder("DOCX", "XML", "PDF", "XLSX", "PPTX", "TXT", "CSV");
    }

    @Test
    void fileTypeCheckAtV9StillHasOnlyTheOriginalTypes() throws SQLException {
        // Absicherung der Testannahme "vorher": V1-V9 erlauben nur DOCX/XML, erst V10 erweitert.
        Path dbFile = tempDir.resolve("v9-check.db");
        assertThat(migrate(dbFile, VERSION_BEFORE).success).isTrue();

        assertThat(fileTypeCheckValues(tableSql(dbFile, "generated_file"))).containsExactlyInAnyOrder("DOCX", "XML");
        try (Connection connection = openWithForeignKeys(dbFile)) {
            assertThatThrownBy(() -> insertGeneratedFile(connection, null, "stored.pdf", "PDF"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("CHECK constraint failed");
        }
        assertThat(queryStrings(dbFile, "SELECT version FROM flyway_schema_history "
                + "WHERE version IS NOT NULL ORDER BY installed_rank"))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    @Test
    void mailBatchForeignKeyListIsUnchangedByRebuild() throws SQLException {
        Path dbFile = databaseAtV9WithData("mail-batch-fk.db");
        List<String> mailBatchForeignKeysBefore = foreignKeyList(dbFile, "mail_batch");
        List<String> mailBatchColumnsBefore = columnLayout(dbFile, "mail_batch");

        assertThat(migrate(dbFile, VERSION_REBUILD).success).isTrue();

        assertThat(foreignKeyList(dbFile, "mail_batch")).containsExactlyElementsOf(mailBatchForeignKeysBefore);
        assertThat(columnLayout(dbFile, "mail_batch")).containsExactlyElementsOf(mailBatchColumnsBefore);
        assertThat(queryStrings(dbFile, "SELECT m.id, g.file_type FROM mail_batch m "
                + "LEFT JOIN generated_file g ON g.id = m.generated_file_id ORDER BY m.id"))
                .containsExactly("10|DOCX", "11|null");
    }
}
