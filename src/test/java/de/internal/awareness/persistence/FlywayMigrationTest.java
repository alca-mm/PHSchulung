package de.internal.awareness.persistence;

import de.internal.awareness.campaign.Campaign;
import de.internal.awareness.contact.Contact;
import de.internal.awareness.file.GeneratedFile;
import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.recipient.CampaignRecipient;
import de.internal.awareness.tracking.MailTrackingEvent;
import de.internal.awareness.tracking.TrackingEvent;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Flyway-Migrationen ohne Spring-Kontext gegen isolierte SQLite-Dateien in einem {@link TempDir}
 * (die Entwicklungs-DB ./data/app.db wird nie beruehrt).
 * <ul>
 *   <li>Fall 1: V1 migriert eine LEERE Datenbank erfolgreich; ein zweiter Lauf fuehrt nichts aus.</li>
 *   <li>Fall 2: Alle erwarteten Tabellen (inkl. flyway_schema_history) existieren danach.</li>
 *   <li>Fall 3: Hibernate {@code validate} akzeptiert das Flyway-Schema und erkennt Abweichungen.</li>
 *   <li>Fall 4: {@code PRAGMA foreign_key_check} ist direkt nach der Migration leer.</li>
 *   <li>Fall 7: Eine Alt-DB ohne Schema-History (frueher von Hibernate erzeugt) wird NICHT still
 *       uebernommen (kein baselineOnMigrate) und NICHT veraendert.</li>
 * </ul>
 * Hinweis: Die Erwartungen zur Migrationsanzahl/Zielversion werden bei jeder neuen Migration bewusst
 * angepasst. Aktueller Stand: V1 (Init) + V2 (Campaign-Mailfelder) + V3 (Empfaenger-Versandstatus) +
 * V4 (Unique-Index campaign_id+email) + V5 (generated_file) + V6 (contact) + V7 (mail_batch/mail_delivery)
 * + V8 (mail_delivery.tracking_token_hash) + V9 (mail_tracking_event)
 * + V10 (generated_file.file_type CHECK um PDF/XLSX/PPTX/TXT/CSV erweitert) = 10 Migrationen, Zielversion "10".
 */
class FlywayMigrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    @TempDir
    Path tempDir;

    private static String jdbcUrl(Path dbFile) {
        return "jdbc:sqlite:" + dbFile.toAbsolutePath();
    }

    /** Flyway mit Standardkonfiguration (baselineOnMigrate=false, wie in der Anwendung). */
    private static Flyway flywayFor(Path dbFile) {
        return Flyway.configure()
                .dataSource(jdbcUrl(dbFile), null, null)
                .locations(MIGRATION_LOCATION)
                .load();
    }

    private static List<String> queryStrings(Path dbFile, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl(dbFile));
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static List<String> userTables(Path dbFile) throws SQLException {
        return queryStrings(dbFile,
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name");
    }

    @Test
    void migratesEmptyDatabaseWithAllMigrationsToLatestVersion() {
        Path dbFile = tempDir.resolve("empty.db");

        MigrateResult result = flywayFor(dbFile).migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(10);
        assertThat(result.initialSchemaVersion).isNull();
        assertThat(result.targetSchemaVersion).isEqualTo("10");

        MigrationInfo[] applied = flywayFor(dbFile).info().applied();
        assertThat(applied).hasSize(10);
        assertThat(applied)
                .extracting(mi -> mi.getVersion().getVersion(), MigrationInfo::getScript, MigrationInfo::getState)
                .containsExactly(
                        tuple("1", "V1__init_schema.sql", MigrationState.SUCCESS),
                        tuple("2", "V2__add_campaign_mail_fields.sql", MigrationState.SUCCESS),
                        tuple("3", "V3__add_recipient_delivery_status.sql", MigrationState.SUCCESS),
                        tuple("4", "V4__add_recipient_campaign_email_unique.sql", MigrationState.SUCCESS),
                        tuple("5", "V5__add_generated_files.sql", MigrationState.SUCCESS),
                        tuple("6", "V6__add_contacts.sql", MigrationState.SUCCESS),
                        tuple("7", "V7__add_mail_batches_and_deliveries.sql", MigrationState.SUCCESS),
                        tuple("8", "V8__add_mail_delivery_tracking.sql", MigrationState.SUCCESS),
                        tuple("9", "V9__add_mail_tracking_event.sql", MigrationState.SUCCESS),
                        tuple("10", "V10__extend_generated_file_types.sql", MigrationState.SUCCESS));
    }

    @Test
    void secondMigrateRunExecutesNoFurtherMigrations() {
        Path dbFile = tempDir.resolve("rerun.db");
        flywayFor(dbFile).migrate();

        MigrateResult second = flywayFor(dbFile).migrate();

        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isZero();
        assertThat(second.initialSchemaVersion).isEqualTo("10");
        assertThat(flywayFor(dbFile).info().pending()).isEmpty();
        assertThat(flywayFor(dbFile).info().current().getVersion().getVersion()).isEqualTo("10");
    }

    @Test
    void migrationCreatesAllExpectedTables() throws SQLException {
        Path dbFile = tempDir.resolve("tables.db");
        flywayFor(dbFile).migrate();

        assertThat(userTables(dbFile))
                .contains("campaign", "campaign_recipient", "tracking_event",
                        "generated_file", "contact", "mail_batch", "mail_delivery", "mail_tracking_event",
                        "flyway_schema_history");
    }

    @Test
    void freshlyMigratedDatabaseReportsNoForeignKeyProblems() throws SQLException {
        Path dbFile = tempDir.resolve("fkcheck.db");
        flywayFor(dbFile).migrate();

        // foreign_key_check meldet Verletzungen als Zeilen; fehlerhafte FK-Definitionen (z. B. Verweis
        // auf eine nicht eindeutige Spalte) fuehren zu einer SQLException ("foreign key mismatch").
        assertThat(queryStrings(dbFile, "PRAGMA foreign_key_check")).isEmpty();
    }

    @Test
    void hibernateValidateAcceptsMigratedSchemaAndDetectsMissingColumn() throws SQLException {
        Path dbFile = tempDir.resolve("validate.db");
        flywayFor(dbFile).migrate();

        // 1) Unveraendertes Flyway-Schema: Hibernate-Validierung ist erfolgreich.
        assertThatCode(() -> bootstrapHibernateWithValidation(dbFile)).doesNotThrowAnyException();

        // 2) Simulierter Schema-Drift: gemappte Spalte fehlt -> Validierung muss den Start verhindern.
        try (Connection connection = DriverManager.getConnection(jdbcUrl(dbFile));
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE campaign DROP COLUMN description");
        }
        assertThatThrownBy(() -> bootstrapHibernateWithValidation(dbFile))
                .isInstanceOf(SchemaManagementException.class)
                .hasMessageContaining("missing column [description] in table [campaign]");
    }

    @Test
    void hibernateValidateDetectsMissingTrackingHashUniqueIndex() throws SQLException {
        Path dbFile = tempDir.resolve("validate-unique.db");
        flywayFor(dbFile).migrate();

        // Simulierter Drift: Unique-Index der Tracking-Identitaet fehlt. Standard-validate wuerde das
        // nicht bemerken; mit unique_key_validation=NAMED (wie in der Anwendung) muss der Start scheitern.
        try (Connection connection = DriverManager.getConnection(jdbcUrl(dbFile));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX uk_recipient_tracking_token_hash");
        }
        assertThatThrownBy(() -> bootstrapHibernateWithValidation(dbFile))
                .isInstanceOf(SchemaManagementException.class)
                .hasMessageContaining("uk_recipient_tracking_token_hash");
    }

    /**
     * Startet eine minimale Hibernate-SessionFactory (ohne Spring) mit ddl-auto=validate und derselben
     * Unique-Key-Validierung wie die Anwendung gegen die uebergebene DB und schliesst sie sofort wieder
     * (Datei-Handles freigeben, wichtig unter Windows).
     */
    private static void bootstrapHibernateWithValidation(Path dbFile) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", jdbcUrl(dbFile))
                .applySetting("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .applySetting("hibernate.tooling.schema.unique_key_validation", "NAMED")
                .build();
        try (SessionFactory ignored = new MetadataSources(registry)
                .addAnnotatedClass(Campaign.class)
                .addAnnotatedClass(CampaignRecipient.class)
                .addAnnotatedClass(TrackingEvent.class)
                .addAnnotatedClass(GeneratedFile.class)
                .addAnnotatedClass(Contact.class)
                .addAnnotatedClass(MailBatch.class)
                .addAnnotatedClass(MailDelivery.class)
                .addAnnotatedClass(MailTrackingEvent.class)
                .buildMetadata()
                .buildSessionFactory()) {
            // Erfolgreicher Aufbau genuegt: die Schema-Validierung laeuft beim Start der SessionFactory.
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    void legacyDatabaseWithoutSchemaHistoryIsRejectedAndLeftUntouched() throws SQLException {
        // Simuliert eine alte, frueher von Hibernate (ddl-auto=update) erzeugte ./data/app.db:
        // Tabellen vorhanden, aber keine flyway_schema_history.
        Path dbFile = tempDir.resolve("legacy.db");
        String legacyDdl = "CREATE TABLE campaign (id integer primary key, name varchar(255))";
        try (Connection connection = DriverManager.getConnection(jdbcUrl(dbFile));
             Statement statement = connection.createStatement()) {
            statement.execute(legacyDdl);
            statement.execute("INSERT INTO campaign (id, name) VALUES (1, 'Alt-Kampagne')");
        }

        Flyway flyway = flywayFor(dbFile);
        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();

        assertThatThrownBy(flyway::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty schema")
                .hasMessageContaining("no schema history table");

        // Nichts wurde angelegt, geloescht oder veraendert.
        assertThat(userTables(dbFile)).containsExactly("campaign");
        assertThat(queryStrings(dbFile, "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'campaign'"))
                .containsExactly(legacyDdl);
        assertThat(queryStrings(dbFile, "SELECT name FROM pragma_table_info('campaign') ORDER BY cid"))
                .containsExactly("id", "name");
        assertThat(queryStrings(dbFile, "SELECT COUNT(*) FROM campaign")).containsExactly("1");
    }
}
