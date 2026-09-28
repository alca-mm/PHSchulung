package de.internal.awareness.persistence;

import de.internal.awareness.support.SqliteFlywayJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Metadaten des von Flyway erzeugten Schemas, per PRAGMA ueber die Anwendungs-DataSource geprueft
 * (isolierte Test-DB unter target/, nie ./data/app.db).
 * <ul>
 *   <li>Fall 2: Alle erwarteten Tabellen existieren.</li>
 *   <li>Fall 4: Echte Foreign Keys mit ON DELETE/ON UPDATE RESTRICT, Unique-Index auf
 *       tracking_token_hash, Indizes auf den FK-Spalten, kein Unique-Index, der mehrere
 *       LINK_CLICK-Events verhindern wuerde, und {@code PRAGMA foreign_key_check} ohne Befund.</li>
 * </ul>
 * Reine Lesezugriffe ohne Testdaten; eine Testtransaktion (Rollback) ist daher nicht noetig.
 */
@SqliteFlywayJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SchemaMetadataTest {

    private final JdbcTemplate jdbc;

    @Autowired
    SchemaMetadataTest(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    private List<Map<String, Object>> foreignKeys(String table) {
        return jdbc.queryForList(
                "SELECT \"table\", \"from\", \"to\", on_update, on_delete FROM pragma_foreign_key_list(?) ORDER BY id, seq",
                table);
    }

    private List<Map<String, Object>> indexes(String table) {
        return jdbc.queryForList(
                "SELECT name, \"unique\", origin, partial FROM pragma_index_list(?) ORDER BY name", table);
    }

    private List<String> indexColumns(String index) {
        return jdbc.queryForList("SELECT name FROM pragma_index_info(?) ORDER BY seqno", String.class, index);
    }

    private static int intValue(Object value) {
        return ((Number) value).intValue();
    }

    @Test
    void expectedTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", String.class);

        assertThat(tables).contains("campaign", "campaign_recipient", "tracking_event",
                "generated_file", "contact", "mail_batch", "mail_delivery", "mail_tracking_event",
                "flyway_schema_history");
    }

    @Test
    void campaignRecipientHasRestrictingForeignKeyToCampaign() {
        assertThat(foreignKeys("campaign_recipient"))
                .extracting(fk -> fk.get("table"), fk -> fk.get("from"), fk -> fk.get("to"),
                        fk -> fk.get("on_update"), fk -> fk.get("on_delete"))
                .containsExactly(tuple("campaign", "campaign_id", "id", "RESTRICT", "RESTRICT"));
    }

    @Test
    void trackingEventHasRestrictingForeignKeyToCampaignRecipient() {
        assertThat(foreignKeys("tracking_event"))
                .extracting(fk -> fk.get("table"), fk -> fk.get("from"), fk -> fk.get("to"),
                        fk -> fk.get("on_update"), fk -> fk.get("on_delete"))
                .containsExactly(tuple("campaign_recipient", "recipient_id", "id", "RESTRICT", "RESTRICT"));
    }

    @Test
    void campaignHasNoForeignKeys() {
        assertThat(foreignKeys("campaign")).isEmpty();
    }

    @Test
    void campaignRecipientHasUniqueTokenHashIndexAndCampaignIdIndex() {
        // Reihenfolge = ORDER BY name. Neu ab V4: uk_recipient_campaign_email (unique, campaign_id+email).
        assertThat(indexes("campaign_recipient"))
                .extracting(ix -> ix.get("name"), ix -> intValue(ix.get("unique")), ix -> intValue(ix.get("partial")))
                .containsExactly(
                        tuple("ix_recipient_campaign_id", 0, 0),
                        tuple("uk_recipient_campaign_email", 1, 0),
                        tuple("uk_recipient_tracking_token_hash", 1, 0));

        assertThat(indexColumns("uk_recipient_tracking_token_hash")).containsExactly("tracking_token_hash");
        assertThat(indexColumns("ix_recipient_campaign_id")).containsExactly("campaign_id");
        assertThat(indexColumns("uk_recipient_campaign_email")).containsExactly("campaign_id", "email");
    }

    @Test
    void trackingEventHasNonUniqueRecipientIndexAndNoUniqueIndexBlockingRepeatedClicks() {
        List<Map<String, Object>> eventIndexes = indexes("tracking_event");

        assertThat(eventIndexes)
                .extracting(ix -> ix.get("name"), ix -> intValue(ix.get("unique")))
                .contains(tuple("ix_event_recipient_id", 0));
        assertThat(indexColumns("ix_event_recipient_id")).containsExactly("recipient_id");

        // Mehrere LINK_CLICK-Events je Empfaenger muessen moeglich bleiben: kein Unique-Index
        // (auch kein impliziter aus einer UNIQUE-Constraint) auf recipient_id oder event_type.
        List<String> uniqueIndexes = eventIndexes.stream()
                .filter(ix -> intValue(ix.get("unique")) == 1)
                .map(ix -> (String) ix.get("name"))
                .toList();
        for (String uniqueIndex : uniqueIndexes) {
            assertThat(indexColumns(uniqueIndex))
                    .as("Unique-Index %s auf tracking_event", uniqueIndex)
                    .doesNotContain("recipient_id", "event_type");
        }
    }

    @Test
    void schemaContainsExactlyTheIntendedIndexes() {
        // Keine unnoetigen Indizes: ausserhalb der Flyway-History genau die vorgesehenen. Reihenfolge =
        // ORDER BY name. Bestand (V1/V4): ix_event_recipient_id, ix_recipient_campaign_id,
        // uk_recipient_campaign_email, uk_recipient_tracking_token_hash. Neu (V5-V7): FK-Indizes der
        // Dateibibliothek/Historie und die Unique-Indizes fuer stored_filename bzw. contact.email.
        assertThat(jdbc.queryForList(
                "SELECT tbl_name, name FROM sqlite_master WHERE type = 'index' "
                        + "AND tbl_name <> 'flyway_schema_history' ORDER BY name"))
                .extracting(ix -> ix.get("tbl_name"), ix -> ix.get("name"))
                .containsExactly(
                        tuple("mail_batch", "ix_batch_generated_file"),
                        tuple("mail_delivery", "ix_delivery_batch"),
                        tuple("mail_delivery", "ix_delivery_contact"),
                        tuple("tracking_event", "ix_event_recipient_id"),
                        tuple("mail_tracking_event", "ix_mail_tracking_event_delivery"),
                        tuple("campaign_recipient", "ix_recipient_campaign_id"),
                        tuple("contact", "uk_contact_email"),
                        tuple("generated_file", "uk_generated_file_stored_filename"),
                        tuple("mail_delivery", "uk_mail_delivery_tracking_token_hash"),
                        tuple("campaign_recipient", "uk_recipient_campaign_email"),
                        tuple("campaign_recipient", "uk_recipient_tracking_token_hash"));
    }

    @Test
    void generatedFileAndContactHaveNoForeignKeys() {
        assertThat(foreignKeys("generated_file")).isEmpty();
        assertThat(foreignKeys("contact")).isEmpty();
    }

    @Test
    void mailBatchHasNullableRestrictingForeignKeyToGeneratedFile() {
        assertThat(foreignKeys("mail_batch"))
                .extracting(fk -> fk.get("table"), fk -> fk.get("from"), fk -> fk.get("to"),
                        fk -> fk.get("on_update"), fk -> fk.get("on_delete"))
                .containsExactly(tuple("generated_file", "generated_file_id", "id", "RESTRICT", "RESTRICT"));
        // Anhang ist optional: die FK-Spalte darf NULL sein (Versand ohne Anhang).
        assertThat(jdbc.queryForObject(
                "SELECT \"notnull\" FROM pragma_table_info('mail_batch') WHERE name = 'generated_file_id'",
                Integer.class)).isZero();
    }

    @Test
    void mailDeliveryHasRestrictingForeignKeysToBatchAndContact() {
        assertThat(foreignKeys("mail_delivery"))
                .extracting(fk -> fk.get("table"), fk -> fk.get("from"), fk -> fk.get("to"),
                        fk -> fk.get("on_update"), fk -> fk.get("on_delete"))
                .containsExactlyInAnyOrder(
                        tuple("mail_batch", "batch_id", "id", "RESTRICT", "RESTRICT"),
                        tuple("contact", "contact_id", "id", "RESTRICT", "RESTRICT"));
    }

    @Test
    void mailDeliveryHasUniqueTrackingTokenHashIndex() {
        assertThat(indexes("mail_delivery"))
                .extracting(ix -> ix.get("name"), ix -> intValue(ix.get("unique")))
                .contains(tuple("uk_mail_delivery_tracking_token_hash", 1));
        assertThat(indexColumns("uk_mail_delivery_tracking_token_hash")).containsExactly("tracking_token_hash");
    }

    @Test
    void mailTrackingEventHasRestrictingForeignKeyToDeliveryAndAllowsRepeatedClicks() {
        assertThat(foreignKeys("mail_tracking_event"))
                .extracting(fk -> fk.get("table"), fk -> fk.get("from"), fk -> fk.get("to"),
                        fk -> fk.get("on_update"), fk -> fk.get("on_delete"))
                .containsExactly(tuple("mail_delivery", "delivery_id", "id", "RESTRICT", "RESTRICT"));

        // Mehrere Klicks je Zustellung muessen moeglich bleiben: kein Unique-Index auf delivery_id/event_type.
        List<Map<String, Object>> eventIndexes = indexes("mail_tracking_event");
        assertThat(eventIndexes)
                .extracting(ix -> ix.get("name"), ix -> intValue(ix.get("unique")))
                .contains(tuple("ix_mail_tracking_event_delivery", 0));
        List<String> uniqueIndexes = eventIndexes.stream()
                .filter(ix -> intValue(ix.get("unique")) == 1)
                .map(ix -> (String) ix.get("name"))
                .toList();
        for (String uniqueIndex : uniqueIndexes) {
            assertThat(indexColumns(uniqueIndex))
                    .as("Unique-Index %s auf mail_tracking_event", uniqueIndex)
                    .doesNotContain("delivery_id", "event_type");
        }
    }

    @Test
    void contactEmailUniqueIndexIsCaseInsensitive() {
        // COLLATE NOCASE auf dem Unique-Index: 'Max@Example.invalid' und 'max@example.invalid' kollidieren.
        String sql = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = 'uk_contact_email'", String.class);
        assertThat(sql.toUpperCase(java.util.Locale.ROOT)).contains("NOCASE");
        assertThat(indexColumns("uk_contact_email")).containsExactly("email");
    }

    @Test
    void foreignKeyCheckReportsNoViolations() {
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
}
