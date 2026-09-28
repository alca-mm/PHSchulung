package de.internal.awareness.web;

import de.internal.awareness.tracking.TrackingDashboardService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serverseitige Admin-Auswertungsseite ("Tracking-Dashboard") ueber ALLE Versandvorgaenge hinweg: je
 * Zustellung wird angezeigt, ob der eindeutig zugeordnete, serverseitig registrierte Trainingslink geklickt
 * wurde, samt Klickanzahl sowie erstem/letztem Klick. Ein schlichter Filter (Suchtext Name/E-Mail,
 * Versandvorgang, "nur ausgeloeste") schraenkt die Tabelle ein; die Kennzahlen bleiben global.
 *
 * <p>Sicherheit/Datenschutz: Diese Seite ist ausschliesslich fuer angemeldete Admins gedacht und bereits
 * durch die globale Regel {@code anyRequest().authenticated()} (siehe {@code SecurityConfig}) geschuetzt -
 * es ist bewusst KEINE eigene Security-Konfiguration noetig. Die Seite gibt NIEMALS Tokens, Token-Hashes
 * oder sonstige Geheimnisse aus, sondern nur bereits bekannte Empfaenger-Identitaeten (Name/E-Mail) sowie
 * Versandvorgang, Datei, Zeitpunkt und aggregierte Klickzahlen. Es werden bewusst KEINE IP-Adressen,
 * User-Agents oder sonstige Telemetrie erfasst oder angezeigt (Datensparsamkeit).</p>
 *
 * <p>Rein lesend (nur {@code GET /tracking}); es gibt keine schreibenden Endpunkte, daher ist hier auch kein
 * CSRF-Aspekt zu beachten. Die Filter werden ausschliesslich ueber Query-Parameter uebergeben.</p>
 */
@Controller
public class TrackingDashboardController {

    private final TrackingDashboardService trackingDashboardService;

    public TrackingDashboardController(TrackingDashboardService trackingDashboardService) {
        this.trackingDashboardService = trackingDashboardService;
    }

    /**
     * Zeigt das Tracking-Dashboard. Optionaler Filter ueber Suchtext (Name/E-Mail), Versandvorgang
     * ({@code batchId}) und "nur ausgeloeste" Zustellungen. Die Zusammenfassung bleibt global (vom Filter
     * unbeeinflusst); der Filter wirkt nur auf die Tabellenzeilen. Es werden ausschliesslich nicht sensible
     * Anzeige-Daten in das Modell gelegt (keine Tokens/Hashes/Secrets).
     */
    @GetMapping("/tracking")
    public String dashboard(@RequestParam(name = "query", required = false) String query,
                            @RequestParam(name = "batchId", required = false) Long batchId,
                            @RequestParam(name = "onlyTriggered", defaultValue = "false") boolean onlyTriggered,
                            Model model) {
        TrackingDashboardService.Filter filter = TrackingDashboardService.Filter.of(query, batchId, onlyTriggered);
        TrackingDashboardService.DashboardView view = trackingDashboardService.load(filter);
        model.addAttribute("view", view);
        model.addAttribute("summary", view.summary());
        model.addAttribute("rows", view.rows());
        model.addAttribute("batches", view.batches());
        model.addAttribute("filter", view.filter());
        return "tracking/dashboard";
    }
}
