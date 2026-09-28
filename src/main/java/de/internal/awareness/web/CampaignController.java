package de.internal.awareness.web;

import de.internal.awareness.campaign.Campaign;
import de.internal.awareness.campaign.CampaignService;
import de.internal.awareness.config.AppMailProperties;
import de.internal.awareness.mail.CampaignMailService;
import de.internal.awareness.mail.SendNotAllowedException;
import de.internal.awareness.mail.SendReadiness;
import de.internal.awareness.mail.SendSummary;
import de.internal.awareness.recipient.BulkImportResult;
import de.internal.awareness.recipient.CampaignRecipient;
import de.internal.awareness.recipient.DuplicateRecipientException;
import de.internal.awareness.recipient.RecipientService;
import de.internal.awareness.recipient.RecipientStats;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

/**
 * Serverseitige Verwaltungsoberflaeche fuer Kampagnen, Empfaenger und den bewussten One-Click-Versand.
 *
 * <p>Sicherheitsrelevant: Es werden NIE Tracking-Hashes/-Tokens oder SMTP-Secrets an die View uebergeben.
 * Der Versand wird ausschliesslich durch eine bewusste Aktion mit gesetzter Bestaetigungs-Checkbox
 * ausgeloest ({@code POST /campaigns/{id}/send} mit {@code confirmed=true}); ohne Bestaetigung passiert
 * nichts. Die eigentlichen Schutzschalter (Live-Send, Absender-/Empfaenger-Allowlist, SMTP-Konfiguration)
 * setzt {@link CampaignMailService} durch.</p>
 */
@Controller
public class CampaignController {

    private final CampaignService campaignService;
    private final RecipientService recipientService;
    private final CampaignMailService campaignMailService;
    private final AppMailProperties appMailProperties;

    public CampaignController(CampaignService campaignService,
                             RecipientService recipientService,
                             CampaignMailService campaignMailService,
                             AppMailProperties appMailProperties) {
        this.campaignService = campaignService;
        this.recipientService = recipientService;
        this.campaignMailService = campaignMailService;
        this.appMailProperties = appMailProperties;
    }

    /** Einstieg: direkt zur Kampagnenuebersicht. */
    @GetMapping("/")
    public String index() {
        return "redirect:/campaigns";
    }

    /** Uebersicht aller Kampagnen inkl. abgeleitetem Versandstatus je Kampagne. */
    @GetMapping("/campaigns")
    public String list(Model model) {
        List<CampaignRow> rows = new ArrayList<>();
        for (Campaign campaign : campaignService.findAll()) {
            rows.add(new CampaignRow(campaign, recipientService.stats(campaign.getId())));
        }
        model.addAttribute("campaigns", rows);
        return "campaigns/list";
    }

    /** Formular fuer eine neue Kampagne. */
    @GetMapping("/campaigns/new")
    public String newCampaignForm(Model model) {
        if (!model.containsAttribute("campaignForm")) {
            model.addAttribute("campaignForm", new CampaignForm());
        }
        return "campaigns/new";
    }

    /** Legt eine neue Kampagne im Status DRAFT an. */
    @PostMapping("/campaigns")
    public String createCampaign(@Valid @org.springframework.web.bind.annotation.ModelAttribute("campaignForm") CampaignForm form,
                                 BindingResult bindingResult,
                                 RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return "campaigns/new";
        }
        Campaign created = campaignService.create(form.getName(), form.getDescription(), form.getSenderName(),
                form.getSenderEmail(), form.getEmailSubject(), form.getEmailBody());
        redirectAttributes.addFlashAttribute("flashSuccess", "Kampagne angelegt.");
        return "redirect:/campaigns/" + created.getId();
    }

    /** Detailseite einer Kampagne: Felder, Empfaengerliste mit Versandstatus, Versandbereitschaft. */
    @GetMapping("/campaigns/{id}")
    public String detail(@PathVariable Long id, Model model) {
        addDetailModel(model, id);
        return "campaigns/detail";
    }

    /** Einen einzelnen Empfaenger hinzufuegen. */
    @PostMapping("/campaigns/{id}/recipients")
    public String addRecipient(@PathVariable Long id,
                               @Valid @org.springframework.web.bind.annotation.ModelAttribute("singleRecipientForm") SingleRecipientForm form,
                               BindingResult bindingResult,
                               Model model,
                               RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addDetailModel(model, id);
            return "campaigns/detail";
        }
        try {
            recipientService.addRecipient(id, form.getEmail(), form.getDisplayName());
            redirectAttributes.addFlashAttribute("flashSuccess", "Empfaenger hinzugefuegt.");
        } catch (DuplicateRecipientException e) {
            redirectAttributes.addFlashAttribute("flashError",
                    "Diese Adresse ist in der Kampagne bereits vorhanden.");
        }
        return "redirect:/campaigns/" + id;
    }

    /** Mehrere Empfaenger aus dem Mehrfachfeld hinzufuegen. */
    @PostMapping("/campaigns/{id}/recipients/bulk")
    public String addRecipientsBulk(@PathVariable Long id,
                                    @Valid @org.springframework.web.bind.annotation.ModelAttribute("bulkRecipientForm") BulkRecipientForm form,
                                    BindingResult bindingResult,
                                    Model model,
                                    RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addDetailModel(model, id);
            return "campaigns/detail";
        }
        BulkImportResult result = recipientService.importRecipients(id, form.getText());
        redirectAttributes.addFlashAttribute("flashSuccess",
                result.added() + " Empfaenger hinzugefuegt, " + result.duplicates() + " Duplikate ignoriert.");
        if (!result.invalidLines().isEmpty()) {
            redirectAttributes.addFlashAttribute("flashInvalidLines", result.invalidLines());
        }
        return "redirect:/campaigns/" + id;
    }

    /**
     * Bewusster One-Click-Versand an alle (noch nicht versendeten) Empfaenger. Nur mit gesetzter
     * Bestaetigungs-Checkbox; andernfalls passiert nichts. Teilfehler werden zusammengefasst gemeldet.
     */
    @PostMapping("/campaigns/{id}/send")
    public String send(@PathVariable Long id,
                       @RequestParam(name = "confirmed", defaultValue = "false") boolean confirmed,
                       RedirectAttributes redirectAttributes) {
        if (!confirmed) {
            redirectAttributes.addFlashAttribute("flashError",
                    "Bitte bestaetigen Sie die Autorisierung, bevor Sie versenden.");
            return "redirect:/campaigns/" + id;
        }
        try {
            SendSummary summary = campaignMailService.sendToAll(id);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    summary.sent() + " erfolgreich versendet, " + summary.failed() + " fehlgeschlagen"
                            + (summary.skippedAlreadySent() > 0
                                    ? ", " + summary.skippedAlreadySent() + " bereits versendet (uebersprungen)" : "")
                            + (summary.blockedByRecipientAllowlist() > 0
                                    ? ", " + summary.blockedByRecipientAllowlist() + " durch Empfaenger-Allowlist blockiert" : "")
                            + ".");
            if (!summary.failedEmails().isEmpty()) {
                redirectAttributes.addFlashAttribute("flashFailedEmails", summary.failedEmails());
            }
            if (!summary.blockedEmails().isEmpty()) {
                redirectAttributes.addFlashAttribute("flashBlockedEmails", summary.blockedEmails());
            }
        } catch (SendNotAllowedException e) {
            redirectAttributes.addFlashAttribute("flashError", "Versand nicht moeglich.");
            redirectAttributes.addFlashAttribute("flashBlockers", e.getBlockers());
        }
        return "redirect:/campaigns/" + id;
    }

    /** Fuellt das Modell fuer die Detailseite; ergaenzt fehlende Formulare (ohne fehlerbehaftete zu ueberschreiben). */
    private void addDetailModel(Model model, Long id) {
        Campaign campaign = campaignService.getById(id);
        List<CampaignRecipient> recipients = recipientService.findByCampaign(id);
        RecipientStats stats = recipientService.stats(id);
        SendReadiness readiness = campaignMailService.checkReadiness(id);

        model.addAttribute("campaign", campaign);
        model.addAttribute("recipients", recipients);
        model.addAttribute("stats", stats);
        model.addAttribute("readiness", readiness);
        model.addAttribute("liveSendEnabled", appMailProperties.isLiveSendEnabled());
        if (!model.containsAttribute("singleRecipientForm")) {
            model.addAttribute("singleRecipientForm", new SingleRecipientForm());
        }
        if (!model.containsAttribute("bulkRecipientForm")) {
            model.addAttribute("bulkRecipientForm", new BulkRecipientForm());
        }
    }

    /** Zeile der Kampagnenuebersicht: Kampagne samt abgeleitetem Versandstatus. */
    public record CampaignRow(Campaign campaign, RecipientStats stats) {
    }
}
