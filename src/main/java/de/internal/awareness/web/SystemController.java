package de.internal.awareness.web;

import de.internal.awareness.system.SmtpConnectionTester;
import de.internal.awareness.system.SmtpTestResult;
import de.internal.awareness.system.SystemStatusService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

/**
 * Serverseitige Diagnose-Oberflaeche ("System / Einrichtung"): zeigt ausschliesslich nicht sensible
 * Statuswerte (Bereitschaftsflags, Konfigurations-Existenz als Ja/Nein) aus {@link SystemStatusService}.
 *
 * <p>Sicherheitsrelevant: Es werden NIE SMTP-/Admin-Secrets, Passwoerter, Passwort-Laengen, Hashes, Tokens
 * oder rohe Environment-Werte an die View uebergeben; Passwoerter erscheinen nur als "vorhanden: Ja/Nein".
 * Der SMTP-Verbindungstest ({@code POST /system/test-smtp}) versendet KEINE Mail, ist CSRF-geschuetzt und
 * nur fuer eingeloggte Admins erreichbar (die Security-Konfiguration schuetzt alles ausser {@code /t},
 * {@code /login} und {@code /error}). Das Ergebnis ist eine feste, sanitizte Kategorie ohne rohe
 * Serverantworten oder Credentials.</p>
 */
@Controller
public class SystemController {

    private final SystemStatusService systemStatusService;
    private final SmtpConnectionTester smtpConnectionTester;

    public SystemController(SystemStatusService systemStatusService,
                           SmtpConnectionTester smtpConnectionTester) {
        this.systemStatusService = systemStatusService;
        this.smtpConnectionTester = smtpConnectionTester;
    }

    /** Systemseite: Gesamtbereitschaft und je Bereich nicht sensible Statuswerte. */
    @GetMapping("/system")
    public String status(Model model, Principal principal) {
        model.addAttribute("status", systemStatusService.build(principal != null ? principal.getName() : null));
        return "system/status";
    }

    /**
     * Bewusster SMTP-Verbindungstest. Versendet KEINE Mail (auch bei deaktiviertem Live-Send), ist
     * CSRF-geschuetzt (nur POST) und nur fuer eingeloggte Admins erreichbar. Das Ergebnis wird als
     * sanitizte Flash-Meldung (feste Kategorie, keine Secrets/Serverantworten) auf die Systemseite geleitet.
     */
    @PostMapping("/system/test-smtp")
    public String testSmtp(RedirectAttributes redirectAttributes) {
        SmtpTestResult r = smtpConnectionTester.test();
        redirectAttributes.addFlashAttribute("smtpTestMessage", r.message());
        redirectAttributes.addFlashAttribute("smtpTestSuccess", r.success());
        redirectAttributes.addFlashAttribute("smtpTested", true);
        return "redirect:/system";
    }
}
