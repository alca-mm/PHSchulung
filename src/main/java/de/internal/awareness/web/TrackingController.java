package de.internal.awareness.web;

import de.internal.awareness.config.AppTrackingProperties;
import de.internal.awareness.tracking.MailTrackingService;
import de.internal.awareness.tracking.TrackingLinkPolicy;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Oeffentlicher, empfaengerseitiger Trainingslink-Endpoint.
 *
 * <p>Ablauf: {@code GET /t/{token}} -&gt; Token pruefen/hashen -&gt; Zustellung finden -&gt; bei gueltigem
 * Token ein {@code LINK_CLICK}-Ereignis speichern -&gt; anschliessend, falls eine sichere
 * {@code app.tracking.redirect-url} konfiguriert ist, per HTTP-302 auf diese oeffentliche, statische
 * Landingpage umleiten; andernfalls die harmlose serverseitige Trainingsseite anzeigen. Mehrfache Aufrufe
 * erzeugen mehrere Ereignisse.</p>
 *
 * <p>Sicherheit des Umleitungsziels: Das Ziel stammt AUSSCHLIESSLICH aus der Server-Konfiguration
 * ({@link AppTrackingProperties#getRedirectUrl()}), NIEMALS aus dem Request, Token, Pfad oder Query-String -
 * damit ist kein Open-Redirect moeglich. Die konfigurierte URL wird vor dem Umleiten mit
 * {@link TrackingLinkPolicy#isAcceptableBaseUrl(String)} geprueft (nur {@code http}/{@code https}, kein
 * {@code javascript:}/{@code data:}/{@code file:}, keine CR/LF, keine eingebetteten Credentials; HTTPS ausser
 * Loopback). Ist sie leer oder unsicher, wird NICHT umgeleitet, sondern die Trainingsseite gezeigt
 * (abwaertskompatibel).</p>
 *
 * <p>Sicherheit/Datenschutz: Bei unbekanntem/ungueltigem Token wird eine neutrale 404-Seite gezeigt - es wird
 * NICHT preisgegeben, ob ein Empfaenger existiert; es erscheinen keine E-Mail-Adressen, IDs, Tokens,
 * Token-Hashes oder Stacktraces, und es wird NICHT umgeleitet. Die Antwort wird bewusst nicht aggressiv
 * gecacht (kein Pixel-/Open-Tracking) - die No-Cache-Header gelten auch fuer die Umleitungsantwort. Es werden
 * keine IP-/User-Agent-/Fingerprint-Daten gespeichert (siehe {@link MailTrackingService}).</p>
 */
@Controller
public class TrackingController {

    private final MailTrackingService trackingService;
    private final AppTrackingProperties trackingProperties;

    public TrackingController(MailTrackingService trackingService, AppTrackingProperties trackingProperties) {
        this.trackingService = trackingService;
        this.trackingProperties = trackingProperties;
    }

    @GetMapping("/t/{token}")
    public String open(@PathVariable String token, HttpServletResponse response) {
        applyNoCacheHeaders(response);
        boolean registered = trackingService.registerClick(token);
        if (registered) {
            // Umleitungsziel kommt NUR aus der Server-Konfiguration (kein Open-Redirect) und wird vor der
            // Verwendung sicherheitsgeprueft. Leer/unsicher => weiterhin die Trainingsseite (abwaertskompatibel).
            String redirectUrl = trackingProperties.getRedirectUrl();
            if (TrackingLinkPolicy.isAcceptableBaseUrl(redirectUrl)) {
                return "redirect:" + redirectUrl;
            }
            return "tracking/training";
        }
        // Neutral: keine Information darueber, ob ein Empfaenger/Link existiert.
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return "tracking/invalid";
    }

    /** Verhindert aggressives Caching der Trainingsseite (kein Ersatz fuer einen neuen Request). */
    private static void applyNoCacheHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
    }
}
