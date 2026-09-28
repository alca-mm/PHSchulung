package de.internal.awareness.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Absicherung des Test-Konfigurationslayouts (ohne Spring-Kontext).
 *
 * <p>Tests erben die reale Anwendungskonfiguration aus {@code src/main/resources/application.properties}
 * (u. a. {@code PRAGMA foreign_keys} via Hikari) und ueberschreiben nur die Datasource-Isolation in
 * {@code src/test/resources/config/application.properties}. Eine {@code application.properties} im
 * Test-Classpath-Root wuerde die Hauptkonfiguration komplett verdecken. Typische Ursache: eine
 * veraltete Kopie in {@code target/test-classes/} aus der Zeit vor der Umstellung, die
 * {@code mvn test} ohne {@code clean} nicht entfernt.</p>
 */
class TestConfigurationLayoutTest {

    private static final String STALE_HINT = "Die Haupt-application.properties wird von einer Datei im Test-Classpath "
            + "verdeckt (vermutlich veraltete target/test-classes/application.properties). "
            + "Einmalig './mvnw -B clean test' ausfuehren bzw. in der IDE 'Rebuild Project'.";

    @Test
    void rootApplicationPropertiesIsTheMainConfigurationWithForeignKeyPragma() throws IOException {
        URL url = classLoader().getResource("application.properties");

        assertThat(url).as("application.properties auf dem Classpath").isNotNull();
        assertThat(url.toString()).as(STALE_HINT).doesNotContain("test-classes");
        assertThat(load(url).getProperty("spring.datasource.hikari.connection-init-sql"))
                .as("FK-Durchsetzung muss in der Hauptkonfiguration aktiviert sein")
                .isEqualTo("PRAGMA foreign_keys = ON");
    }

    @Test
    void testOverridesIsolateTheDatabaseFromTheDevDatabase() throws IOException {
        URL url = classLoader().getResource("config/application.properties");

        assertThat(url).as("Test-Overrides unter classpath:/config/").isNotNull();
        assertThat(url.toString()).contains("test-classes");
        String datasourceUrl = load(url).getProperty("spring.datasource.url");
        assertThat(datasourceUrl)
                .startsWith("jdbc:sqlite:target/pat-test-")
                .doesNotContain("data/app.db");
    }

    private static ClassLoader classLoader() {
        return TestConfigurationLayoutTest.class.getClassLoader();
    }

    private static Properties load(URL url) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = url.openStream()) {
            properties.load(in);
        }
        return properties;
    }
}
