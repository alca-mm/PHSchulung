package de.internal.awareness.persistence;

import de.internal.awareness.campaign.Campaign;
import de.internal.awareness.campaign.CampaignRepository;
import de.internal.awareness.recipient.CampaignRecipient;
import de.internal.awareness.recipient.CampaignRecipientRepository;
import de.internal.awareness.support.SqliteFlywayJpaTest;
import de.internal.awareness.tracking.TrackingEvent;
import de.internal.awareness.tracking.TrackingEventRepository;
import de.internal.awareness.tracking.TrackingEventType;
import de.internal.awareness.tracking.TrackingTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Foreign-Key-Durchsetzung der Flyway-Constraints (V1: ON DELETE/UPDATE RESTRICT) unter SQLite.
 *
 * <p>SQLite setzt Foreign Keys nur durch, wenn auf der jeweiligen Verbindung
 * {@code PRAGMA foreign_keys = ON} aktiv ist. Die Tests pruefen das Verhalten primaer auf
 * DATENBANKEBENE per rohem JDBC ueber die Datasource der Anwendung, damit weder Bean Validation
 * noch Hibernate eine fehlende FK-Pruefung verdecken koennen. Rohes JDBC laeuft ueber
 * {@link DataSourceUtils} auf der transaktionsgebundenen Verbindung und wird mit der
 * Testtransaktion zurueckgerollt. Eine Constraint-Verletzung bricht in SQLite nur das einzelne
 * Statement ab; die Transaktion bleibt nutzbar (Nachpruefung per JDBC im selben Test).</p>
 *
 * <p>Abgedeckte Faelle:</p>
 * <ol>
 *   <li>INSERT in campaign_recipient mit nicht existierender campaign_id wird abgelehnt.</li>
 *   <li>INSERT in tracking_event mit nicht existierender recipient_id wird abgelehnt.</li>
 *   <li>Kampagne mit Empfaengern kann nicht geloescht werden (DB-Ebene und JPA-Ebene).</li>
 *   <li>Empfaenger mit TrackingEvents kann nicht geloescht werden (DB- und JPA-Ebene);
 *       die Tracking-Historie bleibt erhalten.</li>
 *   <li>Positivkontrollen: gueltige Inserts mit existierenden Eltern-IDs, Loeschen einer Kampagne
 *       ohne Empfaenger und eines Empfaengers ohne Events funktionieren (kein pauschales
 *       Loeschverbot).</li>
 *   <li>Mehrere LINK_CLICK-Events desselben Empfaengers bleiben mit aktiven FKs moeglich.</li>
 * </ol>
 * Die Pruefung von {@code PRAGMA foreign_keys} selbst liegt in {@link ForeignKeyPragmaTest} und
 * {@link ForeignKeyPragmaPooledConnectionsTest}.
 */
@SqliteFlywayJpaTest
class ForeignKeyEnforcementTest {

    private static final String FK_FAILURE_MESSAGE = "FOREIGN KEY constraint failed";

    private static final String INSERT_RECIPIENT_SQL =
            "INSERT INTO campaign_recipient (campaign_id, email, display_name, tracking_token_hash, created_at) "
                    + "VALUES (?, ?, NULL, ?, ?)";

    private static final String INSERT_EVENT_SQL =
            "INSERT INTO tracking_event (recipient_id, event_type, occurred_at) VALUES (?, ?, ?)";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignRecipientRepository recipientRepository;

    @Autowired
    private TrackingEventRepository eventRepository;

    @Autowired
    private TestEntityManager entityManager;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // JdbcTemplate nutzt intern DataSourceUtils -> gleiche transaktionsgebundene Verbindung.
        jdbc = new JdbcTemplate(dataSource);
    }

    // ------------------------------------------------------------------------------------------
    // Fall 1 und 2: Inserts mit nicht existierender Eltern-ID
    // ------------------------------------------------------------------------------------------

    @Test
    void recipientInsertWithUnknownCampaignIdIsRejectedByDatabase() {
        long unknownCampaignId = nonExistingId("campaign");
        String hash = TrackingTokens.generate().tokenHash();

        assertThatThrownBy(() -> executeRaw(INSERT_RECIPIENT_SQL,
                unknownCampaignId, "orphan@example.com", hash, now()))
                .isInstanceOfSatisfying(SQLiteException.class,
                        ForeignKeyEnforcementTest::assertChildInsertRejectedByForeignKey);

        // Die verwaiste Zeile darf nicht gespeichert worden sein.
        assertThat(count("SELECT COUNT(*) FROM campaign_recipient WHERE tracking_token_hash = ?", hash)).isZero();
        assertThat(count("SELECT COUNT(*) FROM campaign_recipient WHERE campaign_id = ?", unknownCampaignId)).isZero();
    }

    @Test
    void trackingEventInsertWithUnknownRecipientIdIsRejectedByDatabase() {
        long unknownRecipientId = nonExistingId("campaign_recipient");

        assertThatThrownBy(() -> executeRaw(INSERT_EVENT_SQL,
                unknownRecipientId, TrackingEventType.LINK_CLICK.name(), now()))
                .isInstanceOfSatisfying(SQLiteException.class,
                        ForeignKeyEnforcementTest::assertChildInsertRejectedByForeignKey);

        assertThat(count("SELECT COUNT(*) FROM tracking_event WHERE recipient_id = ?", unknownRecipientId)).isZero();
    }

    // ------------------------------------------------------------------------------------------
    // Fall 3: Kampagne mit Empfaengern ist nicht loeschbar
    // ------------------------------------------------------------------------------------------

    @Test
    void campaignWithRecipientsCannotBeDeletedAtDatabaseLevel() {
        Campaign campaign = newCampaign("Kampagne mit Empfaengern (DB)");
        newRecipient(campaign, "a@example.com");
        newRecipient(campaign, "b@example.com");

        assertThatThrownBy(() -> executeRaw("DELETE FROM campaign WHERE id = ?", campaign.getId()))
                .isInstanceOfSatisfying(SQLiteException.class, this::assertParentDeleteRestrictedByForeignKey);

        assertThat(count("SELECT COUNT(*) FROM campaign WHERE id = ?", campaign.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM campaign_recipient WHERE campaign_id = ?", campaign.getId()))
                .isEqualTo(2);
    }

    @Test
    void campaignWithRecipientsCannotBeDeletedViaRepository() {
        Campaign campaign = newCampaign("Kampagne mit Empfaengern (JPA)");
        newRecipient(campaign, "a@example.com");
        newRecipient(campaign, "b@example.com");
        entityManager.flush();
        entityManager.clear();

        Campaign loaded = campaignRepository.findById(campaign.getId()).orElseThrow();
        campaignRepository.delete(loaded);

        // flush() ueber das Repository -> Spring-Exception-Translation greift (DataAccessException).
        // Empirisch: JpaSystemException <- GenericJDBCException <- SQLiteException, da der
        // Community-SQLiteDialect SQLITE_CONSTRAINT nicht als ConstraintViolationException abbildet.
        // Daher Pruefung auf die Basisklasse DataAccessException plus Meldung/Root-Cause.
        assertThatThrownBy(campaignRepository::flush)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(FK_FAILURE_MESSAGE)
                .rootCause()
                .isInstanceOfSatisfying(SQLiteException.class, this::assertParentDeleteRestrictedByForeignKey);

        // Nachpruefung per JDBC (die Hibernate-Session ist nach dem fehlgeschlagenen Flush unbrauchbar).
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE id = ?", campaign.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM campaign_recipient WHERE campaign_id = ?", campaign.getId()))
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------------------------------
    // Fall 4: Empfaenger mit TrackingEvents ist nicht loeschbar, Historie bleibt erhalten
    // ------------------------------------------------------------------------------------------

    @Test
    void recipientWithTrackingEventsCannotBeDeletedAtDatabaseLevel() {
        CampaignRecipient recipient = newRecipient(newCampaign("Kampagne (DB)"), "a@example.com");
        eventRepository.saveAndFlush(new TrackingEvent(recipient, TrackingEventType.LINK_CLICK));
        eventRepository.saveAndFlush(new TrackingEvent(recipient, TrackingEventType.LINK_CLICK));

        assertThatThrownBy(() -> executeRaw("DELETE FROM campaign_recipient WHERE id = ?", recipient.getId()))
                .isInstanceOfSatisfying(SQLiteException.class, this::assertParentDeleteRestrictedByForeignKey);

        assertThat(count("SELECT COUNT(*) FROM campaign_recipient WHERE id = ?", recipient.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM tracking_event WHERE recipient_id = ?", recipient.getId()))
                .isEqualTo(2);
    }

    @Test
    void recipientWithTrackingEventsCannotBeDeletedViaRepository() {
        CampaignRecipient recipient = newRecipient(newCampaign("Kampagne (JPA)"), "a@example.com");
        eventRepository.saveAndFlush(new TrackingEvent(recipient, TrackingEventType.LINK_CLICK));
        eventRepository.saveAndFlush(new TrackingEvent(recipient, TrackingEventType.LINK_CLICK));
        entityManager.flush();
        entityManager.clear();

        CampaignRecipient loaded = recipientRepository.findById(recipient.getId()).orElseThrow();
        recipientRepository.delete(loaded);

        assertThatThrownBy(recipientRepository::flush)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(FK_FAILURE_MESSAGE)
                .rootCause()
                .isInstanceOfSatisfying(SQLiteException.class, this::assertParentDeleteRestrictedByForeignKey);

        assertThat(count("SELECT COUNT(*) FROM campaign_recipient WHERE id = ?", recipient.getId())).isEqualTo(1);
        // Die Tracking-Historie darf nicht verschwinden.
        assertThat(count("SELECT COUNT(*) FROM tracking_event WHERE recipient_id = ?", recipient.getId()))
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------------------------------
    // Fall 5: Positivkontrollen (Restriktion ist praezise, kein pauschales Verbot)
    // ------------------------------------------------------------------------------------------

    @Test
    void validInsertsWithExistingParentIdsAreAcceptedByDatabase() throws SQLException {
        assertForeignKeysEnforcedOnCurrentConnection();
        Campaign campaign = newCampaign("Kampagne fuer gueltige Inserts");
        String hash = TrackingTokens.generate().tokenHash();

        assertThat(executeRaw(INSERT_RECIPIENT_SQL, campaign.getId(), "valid@example.com", hash, now()))
                .isEqualTo(1);
        long recipientId = jdbc.queryForObject(
                "SELECT id FROM campaign_recipient WHERE tracking_token_hash = ?", Long.class, hash);
        assertThat(executeRaw(INSERT_EVENT_SQL, recipientId, TrackingEventType.LINK_CLICK.name(), now()))
                .isEqualTo(1);

        assertThat(count("SELECT COUNT(*) FROM campaign_recipient WHERE campaign_id = ?", campaign.getId()))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM tracking_event WHERE recipient_id = ?", recipientId)).isEqualTo(1);
    }

    @Test
    void campaignWithoutRecipientsCanBeDeleted() throws SQLException {
        assertForeignKeysEnforcedOnCurrentConnection();
        Campaign withoutRecipients = newCampaign("Kampagne ohne Empfaenger");
        Campaign withRecipients = newCampaign("Kampagne mit Empfaenger");
        newRecipient(withRecipients, "a@example.com");
        entityManager.flush();
        entityManager.clear();

        // JPA-Ebene: Loeschen der Kampagne ohne Empfaenger ist erlaubt.
        campaignRepository.delete(campaignRepository.findById(withoutRecipients.getId()).orElseThrow());
        campaignRepository.flush();

        assertThat(count("SELECT COUNT(*) FROM campaign WHERE id = ?", withoutRecipients.getId())).isZero();
        // Die andere Kampagne samt Empfaenger bleibt unberuehrt.
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE id = ?", withRecipients.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM campaign_recipient WHERE campaign_id = ?", withRecipients.getId()))
                .isEqualTo(1);
    }

    @Test
    void recipientWithoutTrackingEventsCanBeDeleted() throws SQLException {
        assertForeignKeysEnforcedOnCurrentConnection();
        Campaign campaign = newCampaign("Kampagne");
        CampaignRecipient withoutEvents = newRecipient(campaign, "ohne-events@example.com");
        CampaignRecipient withEvents = newRecipient(campaign, "mit-events@example.com");
        eventRepository.saveAndFlush(new TrackingEvent(withEvents, TrackingEventType.LINK_CLICK));
        entityManager.flush();
        entityManager.clear();

        // JPA-Ebene: Loeschen des Empfaengers ohne Events ist erlaubt.
        recipientRepository.delete(recipientRepository.findById(withoutEvents.getId()).orElseThrow());
        recipientRepository.flush();

        assertThat(count("SELECT COUNT(*) FROM campaign_recipient WHERE id = ?", withoutEvents.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM campaign_recipient WHERE id = ?", withEvents.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM tracking_event WHERE recipient_id = ?", withEvents.getId()))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------
    // Fall 6: Mehrere LINK_CLICK-Events desselben Empfaengers bleiben moeglich
    // ------------------------------------------------------------------------------------------

    @Test
    void multipleLinkClicksForSameRecipientRemainPossibleWithForeignKeysOn() throws SQLException {
        assertForeignKeysEnforcedOnCurrentConnection();
        CampaignRecipient recipient = newRecipient(newCampaign("Kampagne Mehrfachklick"), "a@example.com");

        for (int i = 0; i < 3; i++) {
            assertThat(executeRaw(INSERT_EVENT_SQL,
                    recipient.getId(), TrackingEventType.LINK_CLICK.name(), now())).isEqualTo(1);
        }

        assertThat(count("SELECT COUNT(*) FROM tracking_event WHERE recipient_id = ? AND event_type = ?",
                recipient.getId(), TrackingEventType.LINK_CLICK.name())).isEqualTo(3);
        // Gegenprobe ueber das Repository (dieselbe transaktionsgebundene Verbindung).
        assertThat(eventRepository.countByRecipientAndType(recipient, TrackingEventType.LINK_CLICK)).isEqualTo(3L);
    }

    // ------------------------------------------------------------------------------------------
    // Hilfsmethoden
    // ------------------------------------------------------------------------------------------

    /**
     * Kind-Seite (INSERT mit unbekannter Eltern-ID): echte FK-Verletzung mit primaerem Code
     * SQLITE_CONSTRAINT (19), erweitertem Result-Code SQLITE_CONSTRAINT_FOREIGNKEY (787; der
     * xerial-Treiber liefert erweiterte Result-Codes) und der festen SQLite-Meldung.
     */
    private static void assertChildInsertRejectedByForeignKey(SQLiteException ex) {
        assertThat(ex.getErrorCode()).isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT.code);
        assertThat(ex.getResultCode()).isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY);
        assertThat(ex.getMessage()).contains(FK_FAILURE_MESSAGE);
    }

    /**
     * Eltern-Seite (DELETE einer referenzierten Zeile): SQLite setzt ON DELETE RESTRICT intern als
     * RAISE(ABORT, 'FOREIGN KEY constraint failed') um und meldet daher empirisch den erweiterten
     * Code SQLITE_CONSTRAINT_TRIGGER (1811) statt SQLITE_CONSTRAINT_FOREIGNKEY (dieser kaeme bei
     * NO ACTION). Beide werden akzeptiert; entscheidend sind der primaere Code SQLITE_CONSTRAINT,
     * die feste FK-Meldung und dass das Schema KEINE eigenen Trigger enthaelt - die Ablehnung
     * stammt also sicher aus der FK-Durchsetzung.
     */
    private void assertParentDeleteRestrictedByForeignKey(SQLiteException ex) {
        assertThat(ex.getErrorCode()).isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT.code);
        assertThat(ex.getResultCode()).isIn(
                SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER, SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY);
        assertThat(ex.getMessage()).contains(FK_FAILURE_MESSAGE);
        assertThat(count("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger'"))
                .as("Schema darf keine eigenen Trigger enthalten, die dieselbe Meldung erzeugen koennten")
                .isZero();
    }

    /**
     * Vorbedingung fuer die Positivkontrollen: Ohne aktive FK-Durchsetzung waeren sie wertlos,
     * da dann jedes Insert/Delete ohnehin durchginge.
     */
    private void assertForeignKeysEnforcedOnCurrentConnection() throws SQLException {
        Connection con = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = con.prepareStatement("PRAGMA foreign_keys");
             var rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1))
                    .as("Vorbedingung: PRAGMA foreign_keys muss auf der Testverbindung aktiv (1) sein")
                    .isEqualTo(1);
        } finally {
            DataSourceUtils.releaseConnection(con, dataSource);
        }
    }

    /**
     * Fuehrt ein Statement per rohem JDBC auf der transaktionsgebundenen Verbindung aus. SQLExceptions
     * werden bewusst NICHT uebersetzt, damit die originale {@link SQLiteException} geprueft werden kann.
     */
    private int executeRaw(String sql, Object... params) throws SQLException {
        Connection con = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        } finally {
            DataSourceUtils.releaseConnection(con, dataSource);
        }
    }

    private long count(String sql, Object... params) {
        Long result = jdbc.queryForObject(sql, Long.class, params);
        return result == null ? 0L : result;
    }

    /** Liefert eine ID, die in der Tabelle garantiert nicht existiert. */
    private long nonExistingId(String table) {
        long id = count("SELECT COALESCE(MAX(id), 0) + 1000 FROM " + table);
        assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE id = ?", id)).isZero();
        return id;
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private Campaign newCampaign(String name) {
        return campaignRepository.saveAndFlush(new Campaign(name, "Betreff"));
    }

    private CampaignRecipient newRecipient(Campaign campaign, String email) {
        return recipientRepository.saveAndFlush(
                new CampaignRecipient(campaign, email, TrackingTokens.generate().tokenHash()));
    }
}
