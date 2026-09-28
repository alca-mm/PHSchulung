package de.internal.awareness.web;

import de.internal.awareness.campaign.CampaignNotFoundException;
import de.internal.awareness.file.GeneratedFileNotFoundException;
import de.internal.awareness.mail.MailBatchNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Uebersetzt fachliche "nicht gefunden"-Fehler der Weboberflaeche in kontrollierte 404-Antworten (statt
 * 500). Gilt global fuer alle Controller. Es werden bewusst keine internen Details (Pfade, Stacktraces)
 * an den Benutzer ausgegeben.
 */
@ControllerAdvice
public class WebExceptionHandler {

    @ExceptionHandler(CampaignNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleCampaignNotFound(CampaignNotFoundException ex, Model model) {
        model.addAttribute("campaignId", ex.getCampaignId());
        model.addAttribute("notFoundTitle", "Kampagne nicht gefunden");
        return "error/404";
    }

    @ExceptionHandler(GeneratedFileNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleGeneratedFileNotFound(GeneratedFileNotFoundException ex, Model model) {
        model.addAttribute("notFoundTitle", "Datei nicht gefunden");
        model.addAttribute("notFoundMessage", "Die angeforderte Datei existiert nicht.");
        return "error/404";
    }

    @ExceptionHandler(MailBatchNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleMailBatchNotFound(MailBatchNotFoundException ex, Model model) {
        model.addAttribute("notFoundTitle", "Versandvorgang nicht gefunden");
        model.addAttribute("notFoundMessage", "Der angeforderte Versandvorgang existiert nicht.");
        return "error/404";
    }
}
