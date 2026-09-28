package de.internal.awareness.web;

import de.internal.awareness.contact.Contact;
import de.internal.awareness.contact.ContactService;
import de.internal.awareness.contact.DuplicateContactException;
import de.internal.awareness.recipient.BulkImportResult;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Serverseitige Verwaltungsoberflaeche fuer die globale, kampagnenunabhaengige Empfaengerliste
 * (Kontakte): Uebersicht, einzelner Kontakt und Massenimport per Freitext.
 *
 * <p>Sicherheitsrelevant: Es werden keine Secrets oder sensiblen Werte an die View uebergeben; die
 * Kontaktliste enthaelt bewusst nur Adresse, optionalen Anzeigenamen und Anlagezeitpunkt. Der Aufbau
 * spiegelt {@link CampaignController} (Modell-Fuellung ueber eine private Hilfsmethode, POST-Redirect-GET
 * mit Flash-Meldungen).</p>
 */
@Controller
public class ContactController {

    private final ContactService contactService;

    public ContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    /** Uebersicht der globalen Empfaengerliste inkl. Formulare zum Hinzufuegen (einzeln/mehrere). */
    @GetMapping("/recipients")
    public String list(Model model) {
        addListModel(model);
        return "contacts/list";
    }

    /** Einen einzelnen Kontakt zur globalen Liste hinzufuegen. */
    @PostMapping("/recipients")
    public String addContact(@Valid @ModelAttribute("singleContactForm") ContactForm form,
                             BindingResult bindingResult,
                             Model model,
                             RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addListModel(model);
            return "contacts/list";
        }
        try {
            contactService.addContact(form.getEmail(), form.getDisplayName());
            redirectAttributes.addFlashAttribute("flashSuccess", "Empfänger hinzugefügt.");
        } catch (DuplicateContactException e) {
            redirectAttributes.addFlashAttribute("flashError", "Diese Adresse ist bereits vorhanden.");
        }
        return "redirect:/recipients";
    }

    /** Mehrere Kontakte aus dem Mehrfachfeld zur globalen Liste hinzufuegen. */
    @PostMapping("/recipients/bulk")
    public String addContactsBulk(@Valid @ModelAttribute("bulkContactForm") BulkContactForm form,
                                  BindingResult bindingResult,
                                  Model model,
                                  RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addListModel(model);
            return "contacts/list";
        }
        BulkImportResult result = contactService.importContacts(form.getText());
        redirectAttributes.addFlashAttribute("flashSuccess",
                result.added() + " Empfänger hinzugefügt, " + result.duplicates() + " Duplikate ignoriert.");
        if (!result.invalidLines().isEmpty()) {
            redirectAttributes.addFlashAttribute("flashInvalidLines", result.invalidLines());
        }
        return "redirect:/recipients";
    }

    /** Fuellt das Modell fuer die Liste; ergaenzt fehlende Formulare (ohne fehlerbehaftete zu ueberschreiben). */
    private void addListModel(Model model) {
        List<Contact> contacts = contactService.findAll();
        model.addAttribute("contacts", contacts);
        if (!model.containsAttribute("singleContactForm")) {
            model.addAttribute("singleContactForm", new ContactForm());
        }
        if (!model.containsAttribute("bulkContactForm")) {
            model.addAttribute("bulkContactForm", new BulkContactForm());
        }
    }
}
