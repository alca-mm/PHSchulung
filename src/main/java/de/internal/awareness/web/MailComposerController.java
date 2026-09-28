package de.internal.awareness.web;

import de.internal.awareness.contact.ContactNotFoundException;
import de.internal.awareness.contact.ContactService;
import de.internal.awareness.file.GeneratedFileNotFoundException;
import de.internal.awareness.file.GeneratedFileService;
import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailComposeReadiness;
import de.internal.awareness.mail.MailComposeSummary;
import de.internal.awareness.mail.MailComposerService;
import de.internal.awareness.mail.MailSendRequest;
import de.internal.awareness.mail.SendNotAllowedException;
import de.internal.awareness.tracking.MailTrackingService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Serverseitige Oberflaeche des globalen E-Mail-Composers: Nachricht (Betreff/Text/optionaler Anhang)
 * verfassen, Empfaenger aus der Kontaktliste auswaehlen und - nach bewusster Autorisierungs-Bestaetigung -
 * als individuelle Einzelmails versenden; dazu eine schlichte Versandhistorie.
 *
 * <p>Sicherheitsrelevant: Es werden NIE SMTP-Secrets, Tokens oder Tracking-Hashes an die View uebergeben.
 * Der Versand erfolgt ausschliesslich durch eine bewusste Aktion mit gesetzter Bestaetigungs-Checkbox
 * ({@code POST /mail/send} mit {@code confirmed=true}); ohne Bestaetigung passiert nichts. Absender-,
 * Empfaenger- und Datei-Aufloesung sowie die Schutzschalter (Live-Send, Allowlists, SMTP-Konfiguration)
 * setzt {@link MailComposerService} serverseitig durch; die clientseitige Auswahl wird nicht als
 * vertrauenswuerdig behandelt.</p>
 */
@Controller
public class MailComposerController {

    private final MailComposerService mailComposerService;
    private final ContactService contactService;
    private final GeneratedFileService generatedFileService;
    private final MailTrackingService mailTrackingService;

    public MailComposerController(MailComposerService mailComposerService,
                                  ContactService contactService,
                                  GeneratedFileService generatedFileService,
                                  MailTrackingService mailTrackingService) {
        this.mailComposerService = mailComposerService;
        this.contactService = contactService;
        this.generatedFileService = generatedFileService;
        this.mailTrackingService = mailTrackingService;
    }

    /** Composer-Seite: Absenderanzeige, Versandbereitschaft, Kontaktauswahl und Anhang-Auswahl. */
    @GetMapping("/mail")
    public String compose(Model model) {
        addComposeModel(model);
        return "mail/compose";
    }

    /**
     * Bewusster Versand an die ausgewaehlten Kontakte. Nur mit gesetzter Bestaetigungs-Checkbox; andernfalls
     * passiert nichts. Validierungsfehler zeigen das Formular erneut; fachliche Fehler werden als Flash-Meldung
     * zusammengefasst zurueckgegeben.
     */
    @PostMapping("/mail/send")
    public String send(@Valid @ModelAttribute("mailComposeForm") MailComposeForm form,
                       BindingResult bindingResult,
                       @RequestParam(name = "contactIds", required = false) List<Long> contactIds,
                       @RequestParam(name = "confirmed", defaultValue = "false") boolean confirmed,
                       @RequestParam(name = "insertTrackingLink", defaultValue = "false") boolean insertTrackingLink,
                       Model model,
                       RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addComposeModel(model);
            return "mail/compose";
        }
        if (!confirmed) {
            redirectAttributes.addFlashAttribute("flashError",
                    "Bitte bestaetigen Sie die Autorisierung, bevor Sie versenden.");
            return "redirect:/mail";
        }
        MailSendRequest req = new MailSendRequest(form.getSubject(), form.getBody(),
                form.getGeneratedFileId(), contactIds, insertTrackingLink);
        try {
            MailComposeSummary s = mailComposerService.send(req);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    s.sent() + " erfolgreich versendet, " + s.failed() + " fehlgeschlagen"
                            + (s.blocked() > 0 ? ", " + s.blocked() + " durch Empfaenger-Allowlist blockiert" : "")
                            + ".");
            if (!s.failedEmails().isEmpty()) {
                redirectAttributes.addFlashAttribute("flashFailedEmails", s.failedEmails());
            }
            if (!s.blockedEmails().isEmpty()) {
                redirectAttributes.addFlashAttribute("flashBlockedEmails", s.blockedEmails());
            }
        } catch (SendNotAllowedException e) {
            redirectAttributes.addFlashAttribute("flashError", "Versand nicht moeglich.");
            redirectAttributes.addFlashAttribute("flashBlockers", e.getBlockers());
        } catch (ContactNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashError", "Ein ausgewaehlter Empfaenger existiert nicht.");
        } catch (GeneratedFileNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashError", "Die ausgewaehlte Datei existiert nicht.");
        }
        return "redirect:/mail";
    }

    /**
     * Vorschau / Dry-Run - versendet nichts und persistiert nichts. Baut aus den Formulardaten dieselbe
     * Anfrage wie {@link #send} und laesst {@link MailComposerService#preview} eine reine Anzeige-DTO
     * erzeugen (kein SMTP-Versand, kein MailBatch/MailDelivery/MailTrackingEvent, kein Token). Es werden
     * KEINE Secrets/Tokens/Hashes an die View uebergeben. Validierungsfehler zeigen das Formular erneut;
     * manipulierte Empfaenger-/Datei-Ids fuehren zu einer Flash-Meldung und zurueck zum Composer.
     */
    @PostMapping("/mail/preview")
    public String preview(@Valid @ModelAttribute("mailComposeForm") MailComposeForm form,
            BindingResult bindingResult,
            @RequestParam(name = "contactIds", required = false) java.util.List<Long> contactIds,
            @RequestParam(name = "insertTrackingLink", defaultValue = "false") boolean insertTrackingLink,
            Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) { addComposeModel(model); return "mail/compose"; }
        MailSendRequest req = new MailSendRequest(form.getSubject(), form.getBody(), form.getGeneratedFileId(), contactIds, insertTrackingLink);
        try {
            model.addAttribute("preview", mailComposerService.preview(req));
            return "mail/preview";
        } catch (de.internal.awareness.contact.ContactNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashError", "Ein ausgewaehlter Empfaenger existiert nicht.");
            return "redirect:/mail";
        } catch (de.internal.awareness.file.GeneratedFileNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashError", "Die ausgewaehlte Datei existiert nicht.");
            return "redirect:/mail";
        }
    }

    /** Schlichte Versandhistorie mit abgeleiteter Zustell-Statistik je Vorgang. */
    @GetMapping("/mail/history")
    public String history(Model model) {
        model.addAttribute("batches", mailComposerService.historyWithStats());
        return "mail/history";
    }

    /**
     * Detailseite eines Versandvorgangs inkl. Tracking-Auswertung je Empfaenger (Klicks, erster/letzter Klick).
     * Zeigt bewusst KEINE Tokens/Token-Hashes. Unbekannte Batch-Id -&gt; kontrollierte 404 (WebExceptionHandler).
     */
    @GetMapping("/mail/history/{batchId}")
    public String historyDetail(@PathVariable Long batchId, Model model) {
        MailBatch batch = mailComposerService.getBatch(batchId);
        model.addAttribute("batch", batch);
        model.addAttribute("summary", mailTrackingService.batchTracking(batch));
        model.addAttribute("rows", mailTrackingService.deliveryTracking(batch));
        return "mail/history-detail";
    }

    /**
     * Fuellt das Modell fuer die Composer-Seite. Es werden nur nicht sensible Absenderdaten aus der
     * Konfiguration uebergeben (nie Secrets/Tokens). Ein vorhandenes (fehlerbehaftetes) Formular wird nicht
     * ueberschrieben.
     */
    private void addComposeModel(Model model) {
        MailComposeReadiness readiness = mailComposerService.checkReadiness();
        model.addAttribute("readiness", readiness);
        model.addAttribute("contacts", contactService.findAll());
        model.addAttribute("files", generatedFileService.findAll());
        model.addAttribute("senderEmail", readiness.senderEmail());
        model.addAttribute("senderName", readiness.senderName());
        model.addAttribute("liveSendEnabled", readiness.liveSendEnabled());
        // Tracking-Anzeige (KEIN Secret): Basis-URL und ob eine gueltige URL konfiguriert ist.
        model.addAttribute("trackingBaseUrl", mailComposerService.trackingBaseUrl());
        model.addAttribute("trackingConfigured", mailComposerService.trackingConfigured());
        // Bereitschafts-Widget (KEINE Secrets): reine Ja/Nein-Statusflags fuer SMTP und Absender-Allowlist.
        model.addAttribute("smtpReady", mailComposerService.smtpConfigured());
        model.addAttribute("senderAllowed", mailComposerService.senderAllowed());
        if (!model.containsAttribute("mailComposeForm")) {
            model.addAttribute("mailComposeForm", new MailComposeForm());
        }
    }
}
