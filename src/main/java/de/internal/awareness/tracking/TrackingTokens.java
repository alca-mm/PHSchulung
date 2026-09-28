package de.internal.awareness.tracking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Erzeugt kryptografisch starke, nicht erratbare Tracking-Tokens und deren sichere
 * Repraesentation (Hash) fuer die spaetere Zuordnung eingehender Trainingslink-Klicks.
 *
 * <p>Der Klartext-Token (256 Bit Entropie, Base64URL) ist fuer die spaetere URL-Ausgabe gedacht
 * und wird bewusst NICHT persistiert. Gespeichert wird ausschliesslich der SHA-256-Hash
 * ({@link #hash(String)}). Ein spaeter eingehender Token wird gehasht und ueber den Hash
 * nachgeschlagen. Tokenwerte duerfen niemals vollstaendig geloggt werden.</p>
 */
public final class TrackingTokens {

    /** 32 Byte = 256 Bit Entropie. */
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private TrackingTokens() {
    }

    /**
     * Ein neu erzeugtes Token samt seiner speicherbaren Hash-Repraesentation.
     *
     * <p>Sicherheit: Der Klartext-Token ({@link #token()}) bleibt programmgesteuert abrufbar (fuer die
     * einmalige Erzeugung des spaeteren Tracking-Links), wird aber durch das ueberschriebene
     * {@link #toString()} NIE ausgegeben. So kann der Klartext-Token nicht versehentlich ueber
     * String-Konkatenation, Logging oder Fehlermeldungen leaken. Auch der Hash wird redaktiert, um
     * unnoetige Ausgabe sensibler Werte zu vermeiden.</p>
     */
    public record GeneratedToken(String token, String tokenHash) {

        /** Redaktierte Darstellung: enthaelt weder den Klartext-Token noch den Hash. */
        @Override
        public String toString() {
            return "GeneratedToken[token=***redacted***, tokenHash=***redacted***]";
        }
    }

    /** Erzeugt ein neues Zufallstoken und dessen Hash. */
    public static GeneratedToken generate() {
        byte[] raw = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(raw);
        String token = URL_ENCODER.encodeToString(raw);
        return new GeneratedToken(token, hash(token));
    }

    /** Berechnet die speicherbare Hash-Repraesentation eines Tokens (SHA-256, Hex). */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfuegbar", e);
        }
    }
}
