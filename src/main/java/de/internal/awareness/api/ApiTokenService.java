package de.internal.awareness.api;

import de.internal.awareness.config.ApiSecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Serverseitige, im Speicher gehaltene Verwaltung kurzlebiger, widerrufbarer, undurchsichtiger (opaker)
 * Bearer-Tokens fuer den zustandslosen {@code /api/**}-Slice.
 *
 * <p>Sicherheitsmodell (Variante B):</p>
 * <ul>
 *   <li>Ein Token traegt selbst KEINE Daten - er ist reiner Zufall (>= 256 Bit Entropie, {@link SecureRandom},
 *       Base64URL ohne Padding).</li>
 *   <li>Gespeichert wird NIEMALS der Klartext-Token, sondern ausschliesslich sein SHA-256-Hash als Schluessel;
 *       der zugehoerige Datensatz haelt nur Benutzername, Erstell- und (absolutes) Ablaufdatum.</li>
 *   <li>Absolute Gueltigkeit (Default 30 Minuten, konfigurierbar via {@code app.api.token-ttl-seconds}); nach
 *       Ablauf ist der Token ungueltig und wird beim naechsten Zugriff verworfen (lazy purge).</li>
 *   <li>Widerrufbar per {@link #revoke(String)} (Logout).</li>
 *   <li>Es werden NIEMALS Tokens, Hashes oder Passwoerter geloggt.</li>
 * </ul>
 *
 * <p>Warum opaker Bearer-Token statt Cross-Site-Cookie: Third-Party-Cookies (github.io &lt;-&gt; Backend-Origin)
 * sind in modernen Browsern unzuverlaessig. Ein opaker Bearer-Token haelt den {@code /api}-Slice zustandslos und
 * CSRF-immun (keine ambienten Credentials) und erlaubt eine strikte Exakt-Origin-CORS-Allowlist ohne
 * Cookies/Credentials.</p>
 *
 * <p>Thread-Sicherheit: Der Speicher ist eine {@link ConcurrentHashMap}; alle Operationen sind nebenlaeufig
 * sicher.</p>
 */
@Service
public class ApiTokenService {

    /** 32 Byte = 256 Bit Entropie je Token. */
    private static final int TOKEN_BYTES = 32;
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentMap<String, TokenRecord> store = new ConcurrentHashMap<>();
    private final ApiSecurityProperties properties;
    private final Clock clock;

    /** Produktivkonstruktor: nutzt die Systemuhr (UTC). */
    @Autowired
    public ApiTokenService(ApiSecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Testkonstruktor (paketlokal): erlaubt eine steuerbare Uhr zur Simulation von Ablauf. */
    ApiTokenService(ApiSecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Gibt einen frisch erzeugten opaken Token aus und speichert nur dessen Hash samt Ablaufdatum.
     *
     * @param username Benutzername, dem der Token zugeordnet wird (nicht {@code null}/leer)
     * @return der ausgegebene Token (Klartext - nur hier) samt verbleibender Gueltigkeit in Sekunden
     */
    public IssuedToken issue(String username) {
        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String token = URL_ENCODER.encodeToString(raw);

        Instant now = Instant.now(clock);
        long ttlSeconds = properties.getTokenTtlSeconds();
        Instant expiresAt = now.plusSeconds(ttlSeconds);

        store.put(hash(token), new TokenRecord(username, now, expiresAt));
        purgeExpired(now);
        return new IssuedToken(token, username, ttlSeconds);
    }

    /**
     * Prueft einen vorgelegten Klartext-Token.
     *
     * @return den zugeordneten Benutzernamen, falls der Token existiert UND nicht abgelaufen ist; sonst leer.
     *         Abgelaufene Eintraege werden dabei entfernt (lazy).
     */
    public Optional<String> validate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String key = hash(rawToken);
        TokenRecord record = store.get(key);
        if (record == null) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        if (!now.isBefore(record.expiresAt())) {
            // now >= expiresAt -> abgelaufen: verwerfen und als ungueltig behandeln.
            store.remove(key, record);
            return Optional.empty();
        }
        return Optional.of(record.username());
    }

    /** Widerruft einen vorgelegten Klartext-Token (idempotent; unbekannte Tokens sind ein No-op). */
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        store.remove(hash(rawToken));
    }

    /** Aktuelle Anzahl gespeicherter (nicht notwendigerweise noch gueltiger) Tokens - nur fuer Tests/Diagnose. */
    int size() {
        return store.size();
    }

    /** Entfernt opportunistisch alle bereits abgelaufenen Eintraege. */
    private void purgeExpired(Instant now) {
        store.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    /** SHA-256-Hash (Hex) des Tokens - dieselbe Vorgehensweise wie beim Tracking. */
    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfuegbar", e);
        }
    }

    /**
     * Ergebnis von {@link #issue(String)}: der ausgegebene Klartext-Token (nur hier), der Benutzername und die
     * verbleibende Gueltigkeit in Sekunden. {@link #toString()} redaktiert den Token, damit er nicht
     * versehentlich (Logging/Fehlermeldung) leakt.
     */
    public record IssuedToken(String token, String username, long expiresInSeconds) {

        @Override
        public String toString() {
            return "IssuedToken[token=***redacted***, username=" + username
                    + ", expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    /** Serverseitig gespeicherter Datensatz je Token-Hash - enthaelt NIE den Klartext-Token. */
    private record TokenRecord(String username, Instant createdAt, Instant expiresAt) {
    }
}
