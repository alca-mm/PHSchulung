package de.internal.awareness.web;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Betriebs-Endpunkte fuer Plattform-Probes (Liveness/Readiness). Bewusst schlank, oeffentlich (permitAll in
 * {@code SecurityConfig}) und OHNE jegliche sensible Ausgabe:
 *
 * <ul>
 *   <li>{@code GET /health} (Liveness): antwortet immer {@code 200 {"status":"UP"}}, sobald der Prozess laeuft.
 *       Kein Datenbankzugriff, keine Secrets, keine Nutzer-/Konfigurationsdaten.</li>
 *   <li>{@code GET /readiness} (Readiness): prueft mit einem trivialen {@code SELECT 1}, ob die Datenbank
 *       erreichbar ist. Erfolg =&gt; {@code 200 {"status":"UP"}}, sonst {@code 503 {"status":"DOWN"}} - OHNE
 *       Ausnahmedetails/Stacktrace. Es werden KEINE SMTP-/Netzwerk-Checks und KEINE DB-Inhalte ausgegeben.</li>
 * </ul>
 *
 * <p>Die Antwort ist minimales JSON (ein {@link HealthStatus}-Record mit nur einem String-Feld); die
 * Serialisierung uebernimmt Jackson.</p>
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    /** Konstante, teilbare Antworten (unveraenderlicher Record) - kein Zustand, keine Daten. */
    private static final HealthStatus UP = new HealthStatus("UP");
    private static final HealthStatus DOWN = new HealthStatus("DOWN");

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Liveness: nur der laufende Prozess wird bestaetigt (kein DB-Zugriff, keine Details). */
    @GetMapping("/health")
    public ResponseEntity<HealthStatus> health() {
        return ResponseEntity.ok(UP);
    }

    /** Readiness: erst nach erfolgreichem {@code SELECT 1} wird {@code UP} gemeldet, sonst {@code 503 DOWN}. */
    @GetMapping("/readiness")
    public ResponseEntity<HealthStatus> readiness() {
        if (isDatabaseReachable()) {
            return ResponseEntity.ok(UP);
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(DOWN);
    }

    /**
     * Prueft die DB-Erreichbarkeit mit einem trivialen {@code SELECT 1}. Fehler werden datensparsam als kurze
     * Warnung geloggt (OHNE Zugangsdaten) und fuehren zu {@code false}; es gelangt NIE ein Stacktrace oder
     * Detail in die HTTP-Antwort.
     */
    private boolean isDatabaseReachable() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            return resultSet.next();
        } catch (Exception ex) {
            // Datensparsam: nur die Tatsache loggen, KEINE Verbindungs-/Zugangsdaten und kein Stacktrace-Dump.
            log.warn("Readiness-Pruefung fehlgeschlagen: Datenbank nicht erreichbar ({}).",
                    ex.getClass().getSimpleName());
            return false;
        }
    }

    /** Minimale JSON-Nutzlast der Probes: nur ein Statusfeld ({@code UP}/{@code DOWN}). */
    public record HealthStatus(String status) {
    }
}
