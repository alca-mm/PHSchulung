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

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        // Nur gesetzte, getrimmte, nicht-leere Origins uebernehmen; null -> leere Liste (fail-closed:
        // ohne Origins erlaubt CORS keine fremde Herkunft).
        List<String> normalized = new ArrayList<>();
        if (allowedOrigins != null) {
            for (String origin : allowedOrigins) {
                if (origin != null && !origin.trim().isEmpty()) {
                    normalized.add(origin.trim());
                }
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
}
