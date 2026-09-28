package de.internal.awareness.web;

import de.internal.awareness.file.FileContentRequest;
import de.internal.awareness.file.FileTooLargeException;
import de.internal.awareness.file.GeneratedFile;
import de.internal.awareness.file.GeneratedFileService;
import de.internal.awareness.file.GeneratedFileType;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Serverseitige Oberflaeche der Dateibibliothek: listet erzeugte, PASSIVE Trainingsdateien (DOCX, XML, PDF,
 * XLSX, PPTX, TXT, CSV), legt neue an und liefert sie zum Download aus.
 *
 * <p>Typauswahl fail-closed: Der Formularwert wird ueber {@link GeneratedFileType#fromString} streng auf einen
 * bekannten Typ abgebildet; unbekannte Werte werden kontrolliert abgelehnt. Es gibt KEINEN stillen Rueckfall auf
 * ein anderes Format - jeder Typ wird genau als dieser Typ erzeugt.</p>
 *
 * <p>Sicherheitsrelevant: Der Download-Lookup erfolgt ausschliesslich ueber die interne Datenbank-ID; es
 * wird nie ein Dateipfad oder physischer Dateiname aus der URL verwendet. Unbekannte IDs beantwortet der
 * globale {@link WebExceptionHandler} mit einer kontrollierten 404-Antwort.</p>
 */
@Controller
public class FileController {

    private final GeneratedFileService fileService;

    public FileController(GeneratedFileService fileService) {
        this.fileService = fileService;
    }

    /** Uebersicht der Dateibibliothek (neueste zuerst). */
    @GetMapping("/files")
    public String list(Model model) {
        model.addAttribute("files", fileService.findAll());
        return "files/list";
    }

    /** Formular zum Erzeugen einer neuen Datei. */
    @GetMapping("/files/new")
    public String newFileForm(Model model) {
        if (!model.containsAttribute("fileForm")) {
            model.addAttribute("fileForm", new FileForm());
        }
        return "files/new";
    }

    /**
     * Erzeugt eine passive Datei des gewaehlten Typs und legt sie in der Bibliothek ab. XML nutzt zusaetzlich
     * den Root-Namen; alle anderen Typen werden ueber den generischen Dienst-Einstieg mit Titel/Untertitel/Text
     * erzeugt. Unbekannter Typ, unsicherer Dateiname oder zu grosse Datei -&gt; kontrollierte Fehlermeldung.
     */
    @PostMapping("/files")
    public String create(@Valid @ModelAttribute("fileForm") FileForm form,
                         BindingResult bindingResult,
                         RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return "files/new";
        }
        try {
            // fail-closed: unbekannte Werte werfen IllegalArgumentException (kein Standardformat als Rueckfall).
            GeneratedFileType type = GeneratedFileType.fromString(form.getType());
            if (type == GeneratedFileType.XML) {
                fileService.createXml(form.getDisplayName(), form.getFileName(),
                        form.getRootName(), form.getTitle(), form.getBody());
            } else {
                fileService.create(type, form.getDisplayName(), form.getFileName(),
                        new FileContentRequest(form.getTitle(), form.getSubtitle(), form.getBody()));
            }
            redirectAttributes.addFlashAttribute("flashSuccess", "Datei erstellt.");
            return "redirect:/files";
        } catch (IllegalArgumentException | FileTooLargeException e) {
            // Die Meldungen der Dienste sind bewusst unkritisch/ohne interne Details.
            redirectAttributes.addFlashAttribute("flashError",
                    "Datei konnte nicht erstellt werden: " + e.getMessage());
            return "redirect:/files/new";
        }
    }

    /** Liefert den Datei-Inhalt als Download aus. */
    @GetMapping("/files/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        // Sicherheit: nur Lookup ueber interne ID, nie Dateipfad aus der URL.
        GeneratedFile file = fileService.getById(id);
        byte[] content = fileService.loadContent(file);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.getDownloadFilename()).build().toString())
                .body(content);
    }
}
