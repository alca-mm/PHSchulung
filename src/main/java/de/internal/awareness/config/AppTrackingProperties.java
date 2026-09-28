package de.internal.awareness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration des empfaengerbezogenen Awareness-Trackings (Prefix {@code app.tracking}).
 *
 * <p>{@code base-url} ist die oeffentliche Basis-URL, unter der der Trainingslink-Endpoint erreichbar ist
 * (z. B. {@code https://training.example.invalid}); der individuelle Link entsteht als
 * {@code {base-url}/t/{token}}. Der Wert ist KEIN Secret. Gueltigkeit/Sicherheit der URL prueft
 * {@code de.internal.awareness.tracking.TrackingLinkPolicy} (kein {@code javascript:}/{@code data:}/
 * {@code file:}, keine CR/LF, keine eingebetteten Credentials; HTTPS ausser Loopback). Leer => es kann kein
 * Trainingslink erzeugt werden (der Composer meldet dies und der Versand mit Link wird blockiert).</p>
 *
 * <p>{@code redirect-url} ist die OEFFENTLICHE, statische Landingpage, auf die ein Empfaenger NACH dem
 * serverseitigen Tracking weitergeleitet wird (z. B. {@code https://alca-mm.github.io/PHSchulung/}). Der
 * Wert ist KEIN Secret und ist bewusst VERSCHIEDEN von {@code base-url}: {@code base-url} bezeichnet den
 * erreichbaren Spring-Server, der {@code /t/{token}} bereitstellt und den Klick registriert;
 * {@code redirect-url} bezeichnet nur die harmlose Info-/Schulungsseite, auf die anschliessend umgeleitet
 * wird. Fuer die Sicherheit des Umleitungsziels gilt DIESELBE Pruefung wie fuer {@code base-url}
 * ({@code de.internal.awareness.tracking.TrackingLinkPolicy}: nur {@code http}/{@code https}, kein
 * {@code javascript:}/{@code data:}/{@code file:}, keine CR/LF, keine eingebetteten Credentials; HTTPS ausser
 * Loopback). Leer/ungueltig => es wird NICHT umgeleitet, sondern weiterhin die serverseitige Trainingsseite
 * angezeigt (abwaertskompatibles Verhalten). Das Ziel stammt ausschliesslich aus dieser Konfiguration,
 * niemals aus dem Request/Token/Pfad/Query (kein Open-Redirect).</p>
 */
@ConfigurationProperties(prefix = "app.tracking")
public class AppTrackingProperties {

    /** Oeffentliche Basis-URL des Trainingslink-Endpoints. Leer => Trainingslink nicht verfuegbar. */
    private String baseUrl = "";

    /**
     * Oeffentliche, statische Landingpage, auf die NACH dem serverseitigen Tracking weitergeleitet wird.
     * KEIN Secret, VERSCHIEDEN von {@link #baseUrl}. Leer => keine Umleitung (Trainingsseite wird gezeigt).
     */
    private String redirectUrl = "";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public String getRedirectUrl() {
        return redirectUrl;
    }

    public void setRedirectUrl(String redirectUrl) {
        this.redirectUrl = redirectUrl == null ? "" : redirectUrl.trim();
    }
}
