package de.internal.awareness.web;

import de.internal.awareness.tracking.MailTrackingService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Oeffentlicher, empfaengerseitiger Trainingslink-Endpoint.
 *
 * <p>Ablauf: {@code GET /t/{token}} -&gt; Token pruefen/hashen -&gt; Zustellung finden -&gt; bei gueltigem
 * Token ein {@code LINK_CLICK}-Ereignis speichern -&gt; harmlose Trainingsseite anzeigen. Mehrfache Aufrufe
 * erzeugen mehrere Ereignisse.</p>
 *
 * <p>Sicherheit/Datenschutz: Bei unbekanntem/ungueltigem Token wird eine neutrale 404-Seite gezeigt - es wird
 * NICHT preisgegeben, ob ein Empfaenger existiert; es erscheinen keine E-Mail-Adressen, IDs, Tokens,
 * Token-Hashes oder Stacktraces. Die Antwort wird bewusst nicht aggressiv gecacht (kein Pixel-/Open-Tracking).
 * Es werden keine IP-/User-Agent-/Fingerprint-Daten gespeichert (siehe {@link MailTrackingService}).</p>
 */
@Controller
public class TrackingController {

    private final MailTrackingService trackingService;

    public TrackingController(MailTrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @GetMapping("/t/{token}")
    public String open(@PathVariable String token, HttpServletResponse response) {
        applyNoCacheHeaders(response);
        boolean registered = trackingService.registerClick(token);
        if (registered) {
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
