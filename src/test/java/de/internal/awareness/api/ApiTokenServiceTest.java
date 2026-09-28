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
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests des {@link ApiTokenService}: Ausgabe/Pruefung/Ablauf/Widerruf, gehashte Speicherung (der
 * Klartext-Token ist NICHT der gespeicherte Schluessel und taucht in KEINEM gespeicherten Feld auf) sowie
 * Eindeutigkeit zweier ausgegebener Tokens. Keine Spring-Infrastruktur noetig - der Ablauf wird ueber eine
 * steuerbare {@link Clock} simuliert (kein {@code Thread.sleep}).
 */
class ApiTokenServiceTest {

    private static ApiSecurityProperties props(long ttlSeconds) {
        ApiSecurityProperties p = new ApiSecurityProperties();
        p.setTokenTtlSeconds(ttlSeconds);
        return p;
    }

    @Test
    void issueThenValidateReturnsUsername() {
        ApiTokenService service = new ApiTokenService(props(1800));
        ApiTokenService.IssuedToken issued = service.issue("admin");

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.username()).isEqualTo("admin");
        assertThat(issued.expiresInSeconds()).isEqualTo(1800L);
        assertThat(service.validate(issued.token())).contains("admin");
    }

    @Test
    void validateReturnsEmptyForUnknownOrBlankToken() {
        ApiTokenService service = new ApiTokenService(props(1800));
        assertThat(service.validate("does-not-exist")).isEmpty();
        assertThat(service.validate("")).isEmpty();
        assertThat(service.validate(null)).isEmpty();
    }

    @Test
    void expiredTokenIsInvalidAndRemovedLazily() {
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ApiTokenService service = new ApiTokenService(props(60), clock);
        ApiTokenService.IssuedToken issued = service.issue("admin");

        assertThat(service.validate(issued.token())).contains("admin");

        // Uhr ueber das absolute Ablaufdatum hinaus vorstellen.
        clock.advance(Duration.ofSeconds(61));
        assertThat(service.validate(issued.token())).isEmpty();
        // Lazy purge: der abgelaufene Eintrag ist entfernt.
        assertThat(service.size()).isZero();
    }

    @Test
    void revokeMakesTokenInvalid() {
        ApiTokenService service = new ApiTokenService(props(1800));
        ApiTokenService.IssuedToken issued = service.issue("admin");
        assertThat(service.validate(issued.token())).contains("admin");

        service.revoke(issued.token());
        assertThat(service.validate(issued.token())).isEmpty();
    }

    @Test
    void twoIssuesYieldDifferentTokens() {
        ApiTokenService service = new ApiTokenService(props(1800));
        String a = service.issue("admin").token();
        String b = service.issue("admin").token();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @SuppressWarnings("unchecked")
    void storageIsHashedAndNeverHoldsRawToken() throws Exception {
        ApiTokenService service = new ApiTokenService(props(1800));
        ApiTokenService.IssuedToken issued = service.issue("admin");
        String raw = issued.token();

        Field storeField = ApiTokenService.class.getDeclaredField("store");
        storeField.setAccessible(true);
        Map<String, Object> store = (Map<String, Object>) storeField.get(service);

        assertThat(store).hasSize(1);
        String key = store.keySet().iterator().next();

        // Der Schluessel ist der SHA-256-Hex-Hash (64 Zeichen), NICHT der Klartext-Token.
        assertThat(key).isNotEqualTo(raw).hasSize(64).matches("[0-9a-f]{64}");

        // Kein gespeichertes Feld (des Datensatzes) haelt den Klartext-Token.
        Object record = store.get(key);
        for (Field f : record.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object value = f.get(record);
            assertThat(String.valueOf(value)).doesNotContain(raw);
        }
    }

    @Test
    void toStringOfIssuedTokenRedactsRawToken() {
        ApiTokenService service = new ApiTokenService(props(1800));
        ApiTokenService.IssuedToken issued = service.issue("admin");
        assertThat(issued.toString()).doesNotContain(issued.token());
        assertThat(issued.toString()).contains("***redacted***");
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
