package de.internal.awareness.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.internal.awareness.config.ApiSecurityProperties;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests der {@link LoginAttemptService}: Sperre nach N Fehlversuchen, Zuruecksetzen bei Erfolg,
 * Entsperrung nach Ablauf der Sperre bzw. des Fensters, globale Obergrenze, Unabhaengigkeit der Benutzer,
 * positiver {@code Retry-After}-Wert waehrend der Sperre und - datenschutzkritisch - dass ausschliesslich
 * SHA-256-Hashes und NIE der Klartext-Benutzername gespeichert werden. Keine Spring-Infrastruktur noetig;
 * Ablauf wird ueber eine steuerbare {@link Clock} simuliert (kein {@code Thread.sleep}).
 */
class LoginAttemptServiceTest {

    private static ApiSecurityProperties props(int maxAttempts, long windowSeconds, long lockSeconds,
                                               int globalMax) {
        ApiSecurityProperties p = new ApiSecurityProperties();
        p.setLoginMaxAttempts(maxAttempts);
        p.setLoginWindowSeconds(windowSeconds);
        p.setLoginLockSeconds(lockSeconds);
        p.setLoginGlobalMaxAttempts(globalMax);
        return p;
    }

    @Test
    void blockedAfterMaxFailures() {
        LoginAttemptService service = new LoginAttemptService(props(3, 300, 300, 1000));

        service.recordFailure("admin");
        service.recordFailure("admin");
        assertThat(service.isBlocked("admin")).as("nach 2 von 3 Fehlversuchen noch nicht gesperrt").isFalse();

        service.recordFailure("admin");
        assertThat(service.isBlocked("admin")).as("nach 3 Fehlversuchen gesperrt").isTrue();
    }

    @Test
    void successResetsPerUserCounter() {
        LoginAttemptService service = new LoginAttemptService(props(3, 300, 300, 1000));

        service.recordFailure("admin");
        service.recordFailure("admin");
        service.recordSuccess("admin");
        assertThat(service.isBlocked("admin")).isFalse();

        // Nach dem Erfolg beginnt die Zaehlung frisch: 2 weitere Fehlversuche sperren NICHT (2 < 3).
        service.recordFailure("admin");
        service.recordFailure("admin");
        assertThat(service.isBlocked("admin")).as("Zaehler wurde durch Erfolg zurueckgesetzt").isFalse();

        // Erst der dritte Fehlversuch nach dem Reset sperrt wieder.
        service.recordFailure("admin");
        assertThat(service.isBlocked("admin")).isTrue();
    }

    @Test
    void lockExpiresAfterLockSeconds() {
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(props(3, 300, 60, 1000), clock);

        service.recordFailure("admin");
        service.recordFailure("admin");
        service.recordFailure("admin");
        assertThat(service.isBlocked("admin")).isTrue();
        assertThat(service.retryAfterSeconds("admin")).isPositive();

        clock.advance(Duration.ofSeconds(61));
        assertThat(service.isBlocked("admin")).as("nach Ablauf der Sperre wieder frei").isFalse();
        assertThat(service.retryAfterSeconds("admin")).isZero();
    }

    @Test
    void rollingWindowResetsCounting() {
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-01-01T00:00:00Z"));
        // Kleines Fenster (60s), lange Sperre - so isolieren wir die Fenster-Zuruecksetzung der Zaehlung.
        LoginAttemptService service = new LoginAttemptService(props(3, 60, 300, 1000), clock);

        service.recordFailure("admin");
        service.recordFailure("admin");
        assertThat(service.isBlocked("admin")).isFalse();

        // Fenster verstreicht, ohne dass die Grenze erreicht wurde -> Zaehlung beginnt frisch.
        clock.advance(Duration.ofSeconds(61));
        service.recordFailure("admin");
        assertThat(service.isBlocked("admin")).as("Fensterablauf setzt die Zaehlung zurueck").isFalse();
    }

    @Test
    void globalCounterDoesNotBlockUsers() {
        // Der globale Zaehler ist bewusst NICHT blockierend (nur Fruehwarn-Log), damit ein Angreifer den einen
        // Admin nicht per verteilter Fehlversuche aussperren kann (Self-DoS). Hohe Pro-Benutzer-Grenze, damit
        // ausschliesslich der globale Zaehler die (niedrige) globale Schwelle (3) ueberschreitet.
        LoginAttemptService service = new LoginAttemptService(props(100, 300, 300, 3));

        service.recordFailure("user-1");
        service.recordFailure("user-2");
        service.recordFailure("user-3");
        service.recordFailure("user-4");

        // Trotz ueberschrittener globaler Schwelle wird ein anderer (und auch der bisherige) Benutzer NICHT
        // gesperrt - nur die jeweilige Pro-Benutzer-Sperre koennte blockieren (hier nie erreicht).
        assertThat(service.isBlocked("voellig-anderer")).isFalse();
        assertThat(service.retryAfterSeconds("voellig-anderer")).isZero();
        assertThat(service.isBlocked("user-1")).isFalse();
    }

    @Test
    void blockingOneUserDoesNotBlockAnother() {
        LoginAttemptService service = new LoginAttemptService(props(3, 300, 300, 1000));

        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice");

        assertThat(service.isBlocked("alice")).isTrue();
        assertThat(service.isBlocked("bob")).as("Sperre eines Benutzers sperrt keinen anderen").isFalse();
    }

    @Test
    void retryAfterIsPositiveWhileBlockedAndBoundedByLock() {
        LoginAttemptService service = new LoginAttemptService(props(3, 300, 300, 1000));
        service.recordFailure("admin");
        service.recordFailure("admin");
        service.recordFailure("admin");

        long retryAfter = service.retryAfterSeconds("admin");
        assertThat(retryAfter).isPositive().isLessThanOrEqualTo(300L);
    }

    @Test
    void retryAfterIsZeroWhenNotBlocked() {
        LoginAttemptService service = new LoginAttemptService(props(3, 300, 300, 1000));
        assertThat(service.retryAfterSeconds("nie-gesehen")).isZero();
    }

    @Test
    void keyNormalizationIsCaseAndWhitespaceInsensitive() {
        LoginAttemptService service = new LoginAttemptService(props(3, 300, 300, 1000));

        service.recordFailure("Admin");
        service.recordFailure(" admin ");
        service.recordFailure("ADMIN");

        // Alle drei Schreibweisen zaehlen auf denselben Schluessel -> gesperrt, egal wie abgefragt.
        assertThat(service.isBlocked("admin")).isTrue();
        assertThat(service.isBlocked("  AdMiN  ")).isTrue();
        assertThat(service.trackedKeys()).as("nur EIN normalisierter Schluessel").isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void storageHoldsOnlyHashesNeverPlaintextUsername() throws Exception {
        LoginAttemptService service = new LoginAttemptService(props(3, 300, 300, 1000));
        String rawUsername = "Klartext-Benutzer";
        service.recordFailure(rawUsername);

        Field perKeyField = LoginAttemptService.class.getDeclaredField("perKey");
        perKeyField.setAccessible(true);
        Map<String, Object> perKey = (Map<String, Object>) perKeyField.get(service);

        assertThat(perKey).hasSize(1);
        String key = perKey.keySet().iterator().next();

        // Schluessel ist der SHA-256-Hex-Hash (64 Zeichen), NICHT der Klartext (auch nicht kleingeschrieben).
        assertThat(key)
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(rawUsername)
                .isNotEqualTo(rawUsername.toLowerCase(java.util.Locale.ROOT));

        // Kein gespeichertes Feld des Zustands haelt den Klartext-Benutzernamen.
        Object state = perKey.get(key);
        for (Field f : state.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            assertThat(String.valueOf(f.get(state))).doesNotContain(rawUsername);
        }
    }

    @Test
    void resetClearsAllCounters() {
        LoginAttemptService service = new LoginAttemptService(props(3, 300, 300, 3));
        service.recordFailure("admin");
        service.recordFailure("admin");
        service.recordFailure("admin");
        assertThat(service.isBlocked("admin")).isTrue();

        service.reset();
        assertThat(service.isBlocked("admin")).isFalse();
        assertThat(service.trackedKeys()).isZero();
    }

    /** Steuerbare Uhr fuer Ablauf-Tests (kein echtes Warten). */
    private static final class AdjustableClock extends Clock {
        private Instant instant;

        AdjustableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
