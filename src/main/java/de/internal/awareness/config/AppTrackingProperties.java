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
 */
@ConfigurationProperties(prefix = "app.tracking")
public class AppTrackingProperties {

    /** Oeffentliche Basis-URL des Trainingslink-Endpoints. Leer => Trainingslink nicht verfuegbar. */
    private String baseUrl = "";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }
}
