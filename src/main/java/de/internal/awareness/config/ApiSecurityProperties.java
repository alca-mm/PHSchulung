package de.internal.awareness.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration des zustandslosen Cross-Origin-{@code /api/**}-Slice (Prefix {@code app.api}).
 *
 * <p>{@code allowed-origins} ist die exakte CORS-Allowlist fuer den {@code /api/**}-Bereich. Es wird bewusst
 * NIEMALS ein Wildcard ({@code *}) verwendet und keine Origin mit Pfad - eine Origin besteht nur aus
 * Schema + Host (+ optional Port), z. B. {@code https://alca-mm.github.io}. Die Liste darf mehrere exakte
 * Origins enthalten (z. B. zusaetzlich eine lokale Entwicklungs-Origin {@code http://localhost:5500}).
 * Default ist {@code https://alca-mm.github.io} (die statische Auswertungs-/Info-Oberflaeche auf GitHub
 * Pages).</p>
 *
 * <p>{@code token-ttl-seconds} ist die absolute Lebensdauer eines ausgegebenen Bearer-Tokens in Sekunden
 * (Default 1800 = 30 Minuten). Der Token wird serverseitig ausschliesslich als SHA-256-Hash gespeichert
 * (siehe {@code de.internal.awareness.api.ApiTokenService}) und ist widerrufbar.</p>
 *
 * <p>{@code login-*} steuert eine kleine, im Speicher gehaltene, NICHT persistente Anmelde-Drossel
 * (Login-Throttle) fuer {@code POST /api/auth/login} (siehe
 * {@code de.internal.awareness.api.LoginAttemptService}). Sie ist BEWUSST NICHT IP-basiert
 * (Datensparsamkeit: keine IP-/User-Agent-Erfassung); gezaehlt wird je Benutzer ueber den SHA-256-Hash des
 * normalisierten Benutzernamens sowie global als Defense-in-Depth.</p>
 *
 * <p>Hintergrund zur Strategie (Variante B, opaker Bearer-Token statt Cross-Site-Cookie): Third-Party-Cookies
 * (github.io &lt;-&gt; Backend-Origin) sind in modernen Browsern unzuverlaessig. Ein opaker Bearer-Token haelt
 * den {@code /api}-Slice zustandslos und CSRF-immun (keine ambienten Credentials) und erlaubt eine strikte
 * Exakt-Origin-Allowlist OHNE {@code allowCredentials}.</p>
 */
@ConfigurationProperties(prefix = "app.api")
public class ApiSecurityProperties {

    /** Exakte, erlaubte CORS-Origins fuer {@code /api/**}. Kein Wildcard, kein Pfad. */
    private List<String> allowedOrigins = new ArrayList<>(List.of("https://alca-mm.github.io"));

    /** Absolute Gueltigkeit eines Bearer-Tokens in Sekunden (Default 1800 = 30 Minuten). */
    private long tokenTtlSeconds = 1800L;

    /** Max. Fehlversuche je Benutzer(-Hash) innerhalb des Zeitfensters, bevor gesperrt wird (Default 5). */
    private int loginMaxAttempts = 5;

    /** Laenge des rollierenden Zeitfensters fuer die Fehlversuchszaehlung in Sekunden (Default 300). */
    private long loginWindowSeconds = 300L;

    /** Sperrdauer nach Erreichen der Grenze in Sekunden; Client erhaelt 429 + Retry-After (Default 300). */
    private long loginLockSeconds = 300L;

    /** Globale Obergrenze an Fehlversuchen je Zeitfenster ueber ALLE Benutzer (Defense-in-Depth, Default 100). */
    private int loginGlobalMaxAttempts = 100;

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        // Nur gesetzte, getrimmte, nicht-leere Origins uebernehmen; null -> leere Liste (fail-closed:
        // ohne Origins erlaubt CORS keine fremde Herkunft). Zusaetzlich robuste Normalisierung:
        //  - abschliessende Schraegstriche entfernen (eine Origin hat keinen Pfad; "https://h/" -> "https://h"),
        //  - Wildcard "*" wird bewusst NIE uebernommen (uebersprungen), damit nie versehentlich alle Herkuenfte
        //    erlaubt werden.
        List<String> normalized = new ArrayList<>();
        if (allowedOrigins != null) {
            for (String origin : allowedOrigins) {
                if (origin == null) {
                    continue;
                }
                String trimmed = origin.trim().replaceAll("/+$", "");
                if (trimmed.isEmpty() || "*".equals(trimmed)) {
                    continue;
                }
                normalized.add(trimmed);
            }
        }
        this.allowedOrigins = normalized;
    }

    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    public void setTokenTtlSeconds(long tokenTtlSeconds) {
        // Nicht-positive Werte waeren unsinnig (sofort abgelaufen); auf den Default zurueckfallen.
        this.tokenTtlSeconds = tokenTtlSeconds > 0 ? tokenTtlSeconds : 1800L;
    }

    public int getLoginMaxAttempts() {
        return loginMaxAttempts;
    }

    public void setLoginMaxAttempts(int loginMaxAttempts) {
        // Nicht-positive Werte waeren unsinnig (sofortige Sperre/keine Sperre); auf den Default zurueckfallen.
        this.loginMaxAttempts = loginMaxAttempts > 0 ? loginMaxAttempts : 5;
    }

    public long getLoginWindowSeconds() {
        return loginWindowSeconds;
    }

    public void setLoginWindowSeconds(long loginWindowSeconds) {
        this.loginWindowSeconds = loginWindowSeconds > 0 ? loginWindowSeconds : 300L;
    }

    public long getLoginLockSeconds() {
        return loginLockSeconds;
    }

    public void setLoginLockSeconds(long loginLockSeconds) {
        this.loginLockSeconds = loginLockSeconds > 0 ? loginLockSeconds : 300L;
    }

    public int getLoginGlobalMaxAttempts() {
        return loginGlobalMaxAttempts;
    }

    public void setLoginGlobalMaxAttempts(int loginGlobalMaxAttempts) {
        this.loginGlobalMaxAttempts = loginGlobalMaxAttempts > 0 ? loginGlobalMaxAttempts : 100;
    }
}
