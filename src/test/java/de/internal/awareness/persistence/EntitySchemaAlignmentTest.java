package de.internal.awareness.persistence;

import de.internal.awareness.campaign.Campaign;
import de.internal.awareness.campaign.CampaignStatus;
import de.internal.awareness.contact.Contact;
import de.internal.awareness.file.GeneratedFile;
import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.recipient.CampaignRecipient;
import de.internal.awareness.support.SqliteFlywayJpaTest;
import de.internal.awareness.tracking.MailTrackingEvent;
import de.internal.awareness.tracking.TrackingEvent;
import de.internal.awareness.tracking.TrackingEventType;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.descriptor.sql.spi.DdlTypeRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fall 5: Drift-Erkennung zwischen Entity-Mapping und Flyway-Schema - ueber das hinaus, was
 * Hibernate {@code ddl-auto=validate} prueft (validate erkennt weder ueberzaehlige Spalten noch
 * abweichende NOT-NULL-Flags, varchar-Laengen oder z. B. bigint statt integer).
 * <ul>
 *   <li>Je Entity: exakt gleiche Spaltenmenge, NOT NULL passend zur Mapping-Nullability,
 *       varchar(n) passend zur gemappten Laenge, deklarierter Spaltentyp gleich dem DDL-Typ, den
 *       Hibernate fuer das Mapping erzeugen wuerde, Id als INTEGER PRIMARY KEY (rowid-Alias).</li>
 *   <li>Jede Tabelle des Schemas (ausser flyway_schema_history) gehoert zu einer Entity und umgekehrt.</li>
 *   <li>Enum-Spalten tragen CHECK-Constraints, die exakt alle Enum-Konstanten enthalten
 *       (neue Enum-Konstante ohne Migration laesst diesen Test fehlschlagen).</li>
 * </ul>
 * Mapping-Informationen stammen aus dem Hibernate-Laufzeit-Metamodell (MappingMetamodel/SelectableMapping).
 */
@SqliteFlywayJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EntitySchemaAlignmentTest {

    private static final Pattern VARCHAR = Pattern.compile("varchar\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;
    private final SessionFactoryImplementor sessionFactory;

    @Autowired
    EntitySchemaAlignmentTest(DataSource dataSource, EntityManagerFactory entityManagerFactory) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
    }

    /** Eine Spalte laut {@code PRAGMA table_info}. */
    private record DbColumn(String name, String type, boolean notNull, int pk) {
    }

    /** Eine gemappte Spalte laut Hibernate-Metamodell. */
    private record MappedColumn(String name, boolean nullable, boolean identifier, boolean string,
                                Long length, Class<?> javaType, String ddlType) {
    }

    private List<EntityPersister> allPersisters() {
        List<EntityPersister> persisters = new ArrayList<>();
        sessionFactory.getMappingMetamodel().forEachEntityDescriptor(persisters::add);
        return persisters;
    }

    private EntityPersister persister(Class<?> entityClass) {
        return sessionFactory.getMappingMetamodel().getEntityDescriptor(entityClass);
    }

    private static String tableOf(EntityPersister persister) {
        return persister.getMappedTableDetails().getTableName();
    }

    private Map<String, MappedColumn> mappedColumns(EntityPersister persister) {
        Map<String, MappedColumn> columns = new LinkedHashMap<>();
        // EntityPersister#forEachSelectable liefert nur die Attribute, daher die Id separat.
        persister.getIdentifierMapping().forEachSelectable((i, s) -> columns.put(s.getSelectionExpression(),
                toMappedColumn(s, true)));
        persister.forEachSelectable((i, s) -> {
            if (!s.isFormula()) {
                columns.put(s.getSelectionExpression(), toMappedColumn(s, false));
            }
        });
        return columns;
    }

    private MappedColumn toMappedColumn(SelectableMapping s, boolean identifier) {
        // DDL-Typname, den der konfigurierte Dialekt fuer diese Spalte erzeugen wuerde (z. B. varchar(255)).
        DdlTypeRegistry ddlTypes = sessionFactory.getTypeConfiguration().getDdlTypeRegistry();
        return new MappedColumn(
                s.getSelectionExpression(),
                s.isNullable(),
                identifier,
                s.getJdbcMapping().getJdbcType().isString(),
                s.getLength(),
                s.getJdbcMapping().getJavaTypeDescriptor().getJavaTypeClass(),
                ddlTypes.getTypeName(s.getJdbcMapping().getJdbcType().getDdlTypeCode(),
                        s.getLength(), s.getPrecision(), s.getScale()));
    }

    private Map<String, DbColumn> dbColumns(String table) {
        Map<String, DbColumn> columns = new LinkedHashMap<>();
        jdbc.query("SELECT name, type, \"notnull\", pk FROM pragma_table_info(?) ORDER BY cid",
                rs -> {
                    String name = rs.getString("name");
                    columns.put(name, new DbColumn(name, rs.getString("type"), rs.getInt("notnull") == 1,
                            rs.getInt("pk")));
                },
                table);
        return columns;
    }

    private String tableSql(String table) {
        return jdbc.queryForObject("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
                String.class, table);
    }

    /**
     * Liefert die in {@code CHECK (<column> IN ('A', 'B', ...))} erlaubten Werte; leer, falls keine
     * solche CHECK-Constraint existiert. Robust gegen Leerzeichen und zusaetzliche Klammern.
     */
    private Set<String> checkConstraintValues(String table, String column) {
        Pattern check = Pattern.compile(
                "CHECK\\s*\\(\\s*\\(?\\s*\"?" + Pattern.quote(column) + "\"?\\s+IN\\s*\\(([^)]*)\\)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = check.matcher(tableSql(table));
        Set<String> values = new TreeSet<>();
        int matches = 0;
        while (matcher.find()) {
            matches++;
            Matcher literal = Pattern.compile("'([^']*)'").matcher(matcher.group(1));
            while (literal.find()) {
                values.add(literal.group(1));
            }
        }
        assertThat(matches).as("Anzahl CHECK-IN-Constraints fuer %s.%s", table, column).isLessThanOrEqualTo(1);
        return values;
    }

    private static Set<String> enumNames(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(constant -> ((Enum<?>) constant).name())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Entities fuer die parametrisierten Spaltenpruefungen (Vollstaendigkeit siehe Guard-Test unten). */
    static Stream<Class<?>> entityClasses() {
        return Stream.of(Campaign.class, CampaignRecipient.class, TrackingEvent.class,
                GeneratedFile.class, Contact.class, MailBatch.class, MailDelivery.class, MailTrackingEvent.class);
    }

    @Test
    void parameterizedEntityListCoversAllMappedEntities() {
        // Eine neue Entity muss auch die Spaltenpruefungen durchlaufen -> hier ergaenzen.
        assertThat(allPersisters().stream().map(EntityPersister::getMappedClass).collect(Collectors.toSet()))
                .isEqualTo(entityClasses().collect(Collectors.toSet()));
    }

    @ParameterizedTest
    @MethodSource("entityClasses")
    void tableHasExactlyTheMappedColumns(Class<?> entityClass) {
        EntityPersister persister = persister(entityClass);
        String table = tableOf(persister);

        assertThat(dbColumns(table).keySet())
                .as("Spalten von %s (DB) vs. Mapping von %s", table, entityClass.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(mappedColumns(persister).keySet());
    }

    @ParameterizedTest
    @MethodSource("entityClasses")
    void notNullFlagsMatchMappingNullability(Class<?> entityClass) {
        EntityPersister persister = persister(entityClass);
        String table = tableOf(persister);
        Map<String, DbColumn> db = dbColumns(table);

        mappedColumns(persister).values().forEach(mapped -> {
            DbColumn column = db.get(mapped.name());
            assertThat(column).as("Spalte %s.%s", table, mapped.name()).isNotNull();
            if (mapped.identifier()) {
                // INTEGER PRIMARY KEY ist rowid-Alias: SQLite meldet notnull=0, NULL ist trotzdem unmoeglich.
                assertThat(column.pk()).as("PK-Flag %s.%s", table, mapped.name()).isEqualTo(1);
            } else {
                assertThat(column.pk()).as("PK-Flag %s.%s", table, mapped.name()).isZero();
                assertThat(column.notNull())
                        .as("NOT NULL von %s.%s (Mapping nullable=%s)", table, mapped.name(), mapped.nullable())
                        .isEqualTo(!mapped.nullable());
            }
        });
    }

    @ParameterizedTest
    @MethodSource("entityClasses")
    void varcharLengthsMatchMappedLengths(Class<?> entityClass) {
        EntityPersister persister = persister(entityClass);
        String table = tableOf(persister);
        Map<String, DbColumn> db = dbColumns(table);

        List<MappedColumn> stringColumns = mappedColumns(persister).values().stream()
                .filter(MappedColumn::string)
                .toList();
        assertThat(stringColumns).as("String-Spalten von %s", table).isNotEmpty();

        stringColumns.forEach(mapped -> {
            String declaredType = db.get(mapped.name()).type();
            Matcher varchar = VARCHAR.matcher(declaredType);
            assertThat(varchar.matches())
                    .as("Deklarierter Typ von %s.%s ist varchar(n), war: %s", table, mapped.name(), declaredType)
                    .isTrue();
            assertThat(Long.valueOf(varchar.group(1)))
                    .as("varchar-Laenge von %s.%s", table, mapped.name())
                    .isEqualTo(mapped.length());
        });
    }

    @ParameterizedTest
    @MethodSource("entityClasses")
    void identifierIsSingleIntegerPrimaryKeyRowidAlias(Class<?> entityClass) {
        EntityPersister persister = persister(entityClass);
        String table = tableOf(persister);
        List<DbColumn> pkColumns = dbColumns(table).values().stream().filter(c -> c.pk() > 0).toList();

        // IDENTITY unter SQLite setzt einen rowid-Alias voraus: genau eine PK-Spalte vom Typ INTEGER.
        assertThat(pkColumns).extracting(DbColumn::name)
                .containsExactly(persister.getIdentifierMapping().getSelectable(0).getSelectionExpression());
        assertThat(pkColumns.getFirst().type()).isEqualToIgnoringCase("integer");
        assertThat(tableSql(table).toUpperCase(Locale.ROOT)).doesNotContain("WITHOUT ROWID");
    }

    @ParameterizedTest
    @MethodSource("entityClasses")
    void declaredColumnTypesMatchHibernateDdlTypes(Class<?> entityClass) {
        EntityPersister persister = persister(entityClass);
        String table = tableOf(persister);
        Map<String, DbColumn> db = dbColumns(table);

        mappedColumns(persister).values().forEach(mapped -> assertThat(db.get(mapped.name()).type())
                .as("Deklarierter Typ von %s.%s (Hibernate-DDL: %s)", table, mapped.name(), mapped.ddlType())
                .isEqualToIgnoringCase(mapped.ddlType()));
    }

    @Test
    void idAndForeignKeyColumnsAreDeclaredAsInteger() {
        // Die Ids sind per @JdbcTypeCode(INTEGER) gemappt; FK-Spalten erben diesen Typ.
        assertThat(dbColumns("campaign").get("id").type()).isEqualToIgnoringCase("integer");
        assertThat(dbColumns("campaign_recipient")).extractingByKeys("id", "campaign_id")
                .extracting(column -> column.type().toLowerCase(Locale.ROOT))
                .containsExactly("integer", "integer");
        assertThat(dbColumns("tracking_event")).extractingByKeys("id", "recipient_id")
                .extracting(column -> column.type().toLowerCase(Locale.ROOT))
                .containsExactly("integer", "integer");
        assertThat(dbColumns("generated_file").get("id").type()).isEqualToIgnoringCase("integer");
        assertThat(dbColumns("contact").get("id").type()).isEqualToIgnoringCase("integer");
        assertThat(dbColumns("mail_batch")).extractingByKeys("id", "generated_file_id")
                .extracting(column -> column.type().toLowerCase(Locale.ROOT))
                .containsExactly("integer", "integer");
        assertThat(dbColumns("mail_delivery")).extractingByKeys("id", "batch_id", "contact_id")
                .extracting(column -> column.type().toLowerCase(Locale.ROOT))
                .containsExactly("integer", "integer", "integer");
        assertThat(dbColumns("mail_tracking_event")).extractingByKeys("id", "delivery_id")
                .extracting(column -> column.type().toLowerCase(Locale.ROOT))
                .containsExactly("integer", "integer");
    }

    @Test
    void expectedVarcharLengthsAreDeclared() {
        // Explizite Erwartung als Absicherung, dass die generischen Vergleiche oben nicht leer laufen.
        assertThat(dbColumns("campaign")).extractingByKeys("name", "description", "email_subject", "status")
                .extracting(DbColumn::type)
                .containsExactly("varchar(255)", "varchar(2000)", "varchar(255)", "varchar(32)");
        assertThat(dbColumns("campaign_recipient")).extractingByKeys("email", "display_name", "tracking_token_hash")
                .extracting(DbColumn::type)
                .containsExactly("varchar(255)", "varchar(255)", "varchar(64)");
        assertThat(dbColumns("tracking_event")).extractingByKeys("event_type")
                .extracting(DbColumn::type)
                .containsExactly("varchar(32)");
        assertThat(dbColumns("generated_file"))
                .extractingByKeys("display_name", "stored_filename", "download_filename", "file_type", "content_type")
                .extracting(DbColumn::type)
                .containsExactly("varchar(255)", "varchar(255)", "varchar(255)", "varchar(32)", "varchar(255)");
        assertThat(dbColumns("contact")).extractingByKeys("email", "display_name")
                .extracting(DbColumn::type)
                .containsExactly("varchar(255)", "varchar(255)");
        assertThat(dbColumns("mail_batch"))
                .extractingByKeys("subject", "body", "sender_email", "sender_name", "attachment_filename")
                .extracting(DbColumn::type)
                .containsExactly("varchar(255)", "varchar(10000)", "varchar(255)", "varchar(255)", "varchar(255)");
        assertThat(dbColumns("mail_delivery")).extractingByKeys("status", "failure_category", "tracking_token_hash")
                .extracting(DbColumn::type)
                .containsExactly("varchar(32)", "varchar(64)", "varchar(64)");
        assertThat(dbColumns("mail_tracking_event")).extractingByKeys("event_type")
                .extracting(DbColumn::type)
                .containsExactly("varchar(32)");
    }

    @Test
    void fileSizeColumnIsDeclaredAsBigint() {
        // long fileSize -> Hibernate-DDL bigint (nicht integer). declaredColumnTypesMatchHibernateDdlTypes
        // prueft den generischen Abgleich; hier die explizite Erwartung.
        assertThat(dbColumns("generated_file").get("file_size").type()).isEqualToIgnoringCase("bigint");
    }

    @Test
    void everySchemaTableBelongsToAnEntityAndViceVersa() {
        Set<String> entityTables = allPersisters().stream()
                .map(EntitySchemaAlignmentTest::tableOf)
                .collect(Collectors.toCollection(TreeSet::new));
        List<String> schemaTables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' "
                        + "AND name <> 'flyway_schema_history'", String.class);

        assertThat(entityTables).contains("campaign", "campaign_recipient", "tracking_event",
                "generated_file", "contact", "mail_batch", "mail_delivery", "mail_tracking_event");
        assertThat(schemaTables).containsExactlyInAnyOrderElementsOf(entityTables);
    }

    @Test
    void campaignStatusCheckConstraintListsExactlyAllEnumConstants() {
        assertThat(checkConstraintValues("campaign", "status"))
                .isNotEmpty()
                .isEqualTo(enumNames(CampaignStatus.class));
    }

    @Test
    void trackingEventTypeCheckConstraintListsExactlyAllEnumConstants() {
        assertThat(checkConstraintValues("tracking_event", "event_type"))
                .isNotEmpty()
                .isEqualTo(enumNames(TrackingEventType.class));
    }

    @Test
    void everyMappedEnumColumnHasMatchingCheckConstraint() {
        int enumColumns = 0;
        for (EntityPersister persister : allPersisters()) {
            String table = tableOf(persister);
            for (MappedColumn column : mappedColumns(persister).values()) {
                if (column.javaType().isEnum()) {
                    enumColumns++;
                    assertThat(checkConstraintValues(table, column.name()))
                            .as("CHECK-Werte fuer Enum-Spalte %s.%s", table, column.name())
                            .isEqualTo(enumNames(column.javaType()));
                }
            }
        }
        assertThat(enumColumns).as("Anzahl gemappter Enum-Spalten").isGreaterThanOrEqualTo(2);
    }
}
