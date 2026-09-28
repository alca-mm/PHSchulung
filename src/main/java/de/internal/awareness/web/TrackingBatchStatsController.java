package de.internal.awareness.web;

import de.internal.awareness.tracking.TrackingBatchStatsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serverseitige Admin-Auswertungsseite ("Batch-Auswertung") ueber ALLE Versandvorgaenge hinweg: je
 * Versandvorgang ({@code MailBatch}) werden aggregierte Kennzahlen angezeigt - Empfaengerzahl (alle
 * Zustellungen), erfolgreich/fehlgeschlagen versendete Zustellungen, die Anzahl der Empfaenger mit mindestens
 * einer Aktion sowie die daraus abgeleitete Aktionsquote, die Gesamtzahl der Aktionen und erster/letzter
 * Ereigniszeitpunkt.
 *
 * <p>Sicherheit/Datenschutz: Diese Seite ist ausschliesslich fuer angemeldete Admins gedacht und bereits durch
 * die globale Regel {@code anyRequest().authenticated()} (siehe {@code SecurityConfig}) geschuetzt - es ist
 * bewusst KEINE eigene Security-Konfiguration noetig. Die Seite gibt NIEMALS Tokens, Token-Hashes oder sonstige
 * Geheimnisse aus; ebenso werden bewusst KEINE IP-Adressen, User-Agents oder sonstige Telemetrie erfasst oder
 * angezeigt (Datensparsamkeit). Ausgegeben werden nur aggregierte Zaehler, bekannte Batch-Metadaten (Betreff,
 * Anhangname) sowie Ereigniszeitpunkte.</p>
 *
 * <p>Rein lesend (nur {@code GET /tracking/batches}); es gibt keine schreibenden Endpunkte, daher ist hier auch
 * kein CSRF-Aspekt zu beachten.</p>
 */
@Controller
public class TrackingBatchStatsController {

    private final TrackingBatchStatsService trackingBatchStatsService;

    public TrackingBatchStatsController(TrackingBatchStatsService trackingBatchStatsService) {
        this.trackingBatchStatsService = trackingBatchStatsService;
    }

    /**
     * Zeigt die Batch-Auswertung (alle Versandvorgaenge mit mindestens einer Zustellung, neueste zuerst). Es
     * werden ausschliesslich nicht sensible Aggregat-Anzeigedaten in das Modell gelegt (keine Tokens/Hashes/
     * Secrets).
     */
    @GetMapping("/tracking/batches")
    public String batches(Model model) {
        model.addAttribute("batches", trackingBatchStatsService.batchStats());
        return "tracking/batches";
    }
}
