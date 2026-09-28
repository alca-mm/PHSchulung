package de.internal.awareness.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Formularobjekt zum Erzeugen einer passiven Trainingsdatei (DOCX, XML, PDF, XLSX, PPTX, TXT oder CSV).
 *
 * <p>Die Felder werden je nach Typ unterschiedlich genutzt:</p>
 * <ul>
 *   <li><b>DOCX, PDF, PPTX, TXT</b>: {@code title} (Titel), {@code subtitle} (Untertitel), {@code body}
 *       (Freitext); {@code rootName} wird ignoriert.</li>
 *   <li><b>XLSX, CSV</b>: {@code title}, {@code subtitle} und {@code body}; jede Zeile des Freitexts wird zu
 *       einer Tabellenzeile, Tabulatorzeichen trennen die Spalten. {@code rootName} wird ignoriert.</li>
 *   <li><b>XML</b>: {@code rootName} (Name des Wurzelelements), {@code title} (Titel-Inhalt),
 *       {@code body} (Text-Inhalt, wird als Content uebergeben); {@code subtitle} wird ignoriert.</li>
 * </ul>
 *
 * <p>Die erzeugten Dokumente bleiben bewusst passiv/ungefaehrlich; die Endung ergaenzt der Dienst anhand
 * des Typs. Die Pflichtpruefung erfolgt hier per Bean Validation am Formular.</p>
 */
public class FileForm {

    @NotBlank
    @Size(max = 255)
    private String displayName;

    @NotBlank
    @Size(max = 255)
    private String fileName;

    /**
     * Erlaubte Werte (Namen von {@code GeneratedFileType}, Gross-/Kleinschreibung egal): "DOCX", "XML", "PDF",
     * "XLSX", "PPTX", "TXT", "CSV". Standard ist DOCX. Unbekannte Werte werden im Controller fail-closed
     * abgelehnt (kein Rueckfall auf ein anderes Format).
     */
    @NotBlank
    private String type = "DOCX";

    @Size(max = 255)
    private String title;

    @Size(max = 255)
    private String subtitle;

    @Size(max = 10000)
    private String body;

    @Size(max = 100)
    private String rootName;

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getRootName() {
        return rootName;
    }

    public void setRootName(String rootName) {
        this.rootName = rootName;
    }
}
