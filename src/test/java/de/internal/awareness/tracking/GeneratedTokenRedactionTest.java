package de.internal.awareness.tracking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fall 13 / Sicherheits-Regressionstest: {@link TrackingTokens.GeneratedToken#toString()} enthaelt
 * NIEMALS den Klartext-Token (und auch nicht den Hash). Damit kann der Klartext-Token nicht
 * versehentlich ueber String-Konkatenation, Logging oder Fehlermeldungen leaken. Der Klartext bleibt
 * ueber {@link TrackingTokens.GeneratedToken#token()} programmgesteuert abrufbar.
 */
class GeneratedTokenRedactionTest {

    @Test
    void toStringNeverContainsPlaintextTokenOrHash() {
        for (int i = 0; i < 100; i++) {
            TrackingTokens.GeneratedToken generated = TrackingTokens.generate();
            String asString = generated.toString();

            assertThat(asString)
                    .doesNotContain(generated.token())
                    .doesNotContain(generated.tokenHash())
                    .contains("***redacted***");
        }
    }

    @Test
    void plaintextTokenRemainsProgrammaticallyAccessible() {
        TrackingTokens.GeneratedToken generated = TrackingTokens.generate();

        assertThat(generated.token()).isNotBlank();
        assertThat(TrackingTokens.hash(generated.token())).isEqualTo(generated.tokenHash());
    }
}
