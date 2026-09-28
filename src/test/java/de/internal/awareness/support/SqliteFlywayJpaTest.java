package de.internal.awareness.support;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Zusammengesetzte Test-Annotation fuer JPA-Persistenztests gegen die reale SQLite-Konfiguration:
 * <ul>
 *   <li>{@code @DataJpaTest} - JPA-Slice mit transaktionalem Rollback je Testmethode,</li>
 *   <li>{@code replace = NONE} - keine Ersetzung der Datasource (echte SQLite bleibt aktiv),</li>
 *   <li>Flyway-AutoConfig - das Schema wird von denselben db/migration-Skripten erzeugt wie in der
 *       Anwendung; Hibernate validiert es anschliessend (ddl-auto=validate).</li>
 * </ul>
 * In Spring Boot 4.1.1 ist Flyway bereits ueber {@code @DataJpaTest} enthalten (spring-boot-flyway
 * registriert sich fuer {@code AutoConfigureDataSourceInitialization}). Der Import ist daher redundant,
 * bleibt aber bewusst explizit, damit die Abhaengigkeit sichtbar und robust gegen Slice-Aenderungen ist.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
public @interface SqliteFlywayJpaTest {
}
