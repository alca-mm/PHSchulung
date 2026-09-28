package de.internal.awareness.web;

import de.internal.awareness.tracking.TrackingDeliveryNotFoundException;
import de.internal.awareness.tracking.TrackingDeliveryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serverseitige Admin-Detailseite einer einzelnen Zustellung (Composer-Tracking): zeigt die bekannte
 * Empfaenger-Identitaet (Name/E-Mail), den Versandvorgang (Batch/Betreff/Anhang), Zeitpunkt, Status und
 * Versuchszaehler sowie die aufbereitete Tracking-Auswertung (ausgeloest ja/nein, erster/letzter Zeitpunkt,
 * Gesamtanzahl) und die chronologische Ereignis-Timeline (Ereignistyp + Zeitpunkt).
 *
 * <p>Sicherheit/Datenschutz: Diese Seite ist ausschliesslich fuer angemeldete Admins gedacht und bereits durch
 * die globale Regel {@code anyRequest().authenticated()} (siehe {@code SecurityConfig}) geschuetzt - es ist
 * bewusst KEINE eigene Security-Konfiguration noetig. Die Seite gibt NIEMALS Tokens, Token-Hashes, IP-Adressen,
 * User-Agents oder Fingerprints aus, sondern ausschliesslich die ohnehin bekannte Empfaenger-Identitaet sowie
 * Zustell-/Batch-/Datei-/Zeit-/Status-/Versuchsdaten und die Ereignis-Timeline (nur Typ + Zeitpunkt).</p>
 *
 * <p>Rein lesend (nur {@code GET /tracking/delivery/{id}}); es gibt keine schreibenden Endpunkte. Eine unbekannte
 * Id wird - analog zu {@code FileController} / {@code GeneratedFileNotFoundException} - ueber eine dedizierte
 * "nicht gefunden"-Ausnahme in eine kontrollierte 404-Antwort (neutrale Fehlerseite) uebersetzt, niemals in einen
 * rohen 500er. Es werden dabei keine internen Details (Stacktraces, Pfade) preisgegeben.</p>
 */
@Controller
public class TrackingDeliveryController {

    private final TrackingDeliveryService trackingDeliveryService;

    public TrackingDeliveryController(TrackingDeliveryService trackingDeliveryService) {
        this.trackingDeliveryService = trackingDeliveryService;
    }

    /**
     * Zeigt die Detailseite genau einer Zustellung. Existiert keine Zustellung mit dieser Id, wird ueber
     * {@link TrackingDeliveryNotFoundException} (behandelt im globalen {@code WebExceptionHandler}) eine
     * kontrollierte 404-Antwort mit gestylter, neutraler Fehlerseite erzeugt.
     */
    @GetMapping("/tracking/delivery/{id}")
    public String detail(@PathVariable Long id, Model model) {
        TrackingDeliveryService.DeliveryDetail detail = trackingDeliveryService.findDelivery(id)
                .orElseThrow(() -> new TrackingDeliveryNotFoundException(id));
        model.addAttribute("detail", detail);
        return "tracking/delivery-detail";
    }
}
