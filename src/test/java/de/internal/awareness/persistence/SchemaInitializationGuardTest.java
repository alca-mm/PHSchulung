package de.internal.awareness.persistence;

import de.internal.awareness.support.SqliteFlywayJpaTest;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Flyway ist die EINZIGE Schemaquelle; Hibernate validiert nur.
 * <ul>
 *   <li>Fall 1 (Spring): Der Flyway-Bean der Anwendung hat V1 mit Status SUCCESS angewendet,
 *       nichts ist ausstehend.</li>
 *   <li>Fall 3: Der Kontext startet (inkl. Hibernate-Schemavalidierung); die wirksame Einstellung ist
 *       exakt {@code validate}, und keine JPA-Schema-Generierung ist zusaetzlich aktiv.</li>
 *   <li>Fall 6: Keine konkurrierende Schema-Initialisierung (kein schema.sql/data.sql, kein
 *       spring.sql.init.mode=always, keine defer-datasource-initialization, kein Liquibase),
 *       kein baselineOnMigrate, Flyway-clean gesperrt, nur classpath:db/migration.</li>
 * </ul>
 * Ohne Testtransaktion: Flyway holt sich eine eigene Verbindung aus dem Pool (Groesse 1) und wuerde
 * sonst auf die von der Testtransaktion belegte Verbindung warten.
 */
@SqliteFlywayJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SchemaInitializationGuardTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Environment environment;

    @Test
    void flywayBeanHasAppliedV1SuccessfullyAndNothingIsPending() {
        MigrationInfoService info = flyway.info();

        assertThat(info.applied())
                .extracting(mi -> mi.getVersion().getVersion(), MigrationInfo::getScript, MigrationInfo::getState)
                .contains(tuple("1", "V1__init_schema.sql", MigrationState.SUCCESS));
        assertThat(info.applied()).allSatisfy(mi -> assertThat(mi.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(info.pending()).isEmpty();
    }

    @Test
    void hibernateRunsWithSchemaValidationOnly() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(entityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto")).isEqualTo("validate");
        // validate prueft zusaetzlich den benannten Unique-Index der Tracking-Identitaet.
        assertThat(entityManagerFactory.getProperties().get("hibernate.tooling.schema.unique_key_validation"))
                .isEqualTo("NAMED");

        // Die Jakarta-Einstellung haette Vorrang vor hibernate.hbm2ddl.auto und darf nichts erzeugen.
        Object jakartaAction = entityManagerFactory.getProperties()
                .get("jakarta.persistence.schema-generation.database.action");
        assertThat(jakartaAction == null ? "none" : jakartaAction.toString()).isIn("none", "validate");
        assertThat(environment.getProperty("spring.jpa.generate-ddl", Boolean.class, false)).isFalse();
    }

    @Test
    void noSqlInitScriptsOnClasspath() throws IOException {
        for (String script : new String[] {"schema.sql", "data.sql"}) {
            assertThat(new ClassPathResource(script).exists()).as("%s auf dem Classpath", script).isFalse();
        }
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        assertThat(Arrays.asList(resolver.getResources("classpath*:schema-*.sql"))).isEmpty();
        assertThat(Arrays.asList(resolver.getResources("classpath*:data-*.sql"))).isEmpty();
    }

    @Test
    void noCompetingSchemaInitializationIsConfigured() {
        String sqlInitMode = environment.getProperty("spring.sql.init.mode");
        assertThat(sqlInitMode == null ? "" : sqlInitMode).isNotEqualToIgnoringCase("always");
        assertThat(environment.getProperty("spring.jpa.defer-datasource-initialization", Boolean.class, false))
                .isFalse();
        assertThat(ClassUtils.isPresent("liquibase.Liquibase", getClass().getClassLoader())).isFalse();
    }

    @Test
    void flywayConfigurationIsSafeForExistingDatabases() {
        Configuration configuration = flyway.getConfiguration();

        // Eine Alt-DB ohne flyway_schema_history darf NIE still "gebaselined" werden.
        assertThat(configuration.isBaselineOnMigrate()).isFalse();
        // clean() wuerde alle Tabellen loeschen.
        assertThat(configuration.isCleanDisabled()).isTrue();
        // Nachtraeglich veraenderte, bereits angewendete Migrationen werden erkannt.
        assertThat(configuration.isValidateOnMigrate()).isTrue();
        assertThat(configuration.getLocations())
                .extracting(Object::toString)
                .containsExactly("classpath:db/migration");
    }
}
