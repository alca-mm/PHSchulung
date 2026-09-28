package de.internal.awareness.tracking;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-Tests fuer die Erzeugung und das Hashing der Tracking-Identitaet.
 * Sicherheitsrelevant: nicht erratbare Tokens, deterministischer SHA-256-Hash,
 * kein Klartext-Token == Hash.
 */
class TrackingTokensTest {

    @Test
    void generateProducesTokenAndSha256HexHash() {
        TrackingTokens.GeneratedToken generated = TrackingTokens.generate();

        assertThat(generated.token()).isNotBlank();
        assertThat(generated.tokenHash()).isNotBlank();
        // SHA-256 als Hex: 64 Zeichen, nur [0-9a-f]
        assertThat(generated.tokenHash()).hasSize(64).matches("[0-9a-f]{64}");
        // Der Hash ist NICHT der Klartext-Token.
        assertThat(generated.tokenHash()).isNotEqualTo(generated.token());
    }

    @Test
    void tokenHasSufficientEntropyLength() {
        // 32 Zufallsbytes -> Base64URL ohne Padding == 43 Zeichen.
        TrackingTokens.GeneratedToken generated = TrackingTokens.generate();
        assertThat(generated.token()).hasSizeGreaterThanOrEqualTo(43);
    }

    @Test
    void generateProducesUniqueTokensAndHashes() {
        Set<String> tokens = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            TrackingTokens.GeneratedToken generated = TrackingTokens.generate();
            tokens.add(generated.token());
            hashes.add(generated.tokenHash());
        }
        assertThat(tokens).hasSize(1000);
        assertThat(hashes).hasSize(1000);
    }

    @Test
    void hashIsDeterministicForSameToken() {
        String token = TrackingTokens.generate().token();
        assertThat(TrackingTokens.hash(token)).isEqualTo(TrackingTokens.hash(token));
    }

    @Test
    void generatedHashMatchesHashOfToken() {
        TrackingTokens.GeneratedToken generated = TrackingTokens.generate();
        assertThat(TrackingTokens.hash(generated.token())).isEqualTo(generated.tokenHash());
    }

    @Test
    void differentTokensProduceDifferentHashes() {
        String hashA = TrackingTokens.hash("token-a");
        String hashB = TrackingTokens.hash("token-b");
        assertThat(hashA).isNotEqualTo(hashB);
    }
}
