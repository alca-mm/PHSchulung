package de.internal.awareness.file;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Erzeugt PASSIVE {@code .docx}-Dokumente (Office Open XML) mit Apache POI.
 *
 * <p>Sicherheitsgrenze (verbindlich): Es entstehen ausschliesslich normale, ungefaehrliche Textdokumente.
 * Es werden NUR Absaetze und Textlaeufe geschrieben - daher gibt es</p>
 * <ul>
 *   <li>KEINE Makros/VBA (das Format ist {@code .docx}, nicht {@code .docm}; es wird keine vbaProject.bin
 *       eingebettet),</li>
 *   <li>KEINE externen Relationships/Templates, KEINE Hyperlinks auf Tracking-Endpunkte,</li>
 *   <li>KEINE (Remote-)Bilder, KEINE eingebetteten Objekte/OLE, KEINE automatischen Netzwerkzugriffe.</li>
 * </ul>
 * Der Text wird ausschliesslich als Inhalt gesetzt und von POI korrekt in OOXML kodiert.
 *
 * <p>Als {@link FileContentGenerator} fuer {@link GeneratedFileType#DOCX} registriert; der generische Einstieg
 * {@link #generate(FileContentRequest)} delegiert unveraendert an {@link #generate(String, String, String)}.</p>
 */
@Component
public class DocxGenerator implements FileContentGenerator {

    /** Dieser Generator erzeugt {@link GeneratedFileType#DOCX}. */
    @Override
    public GeneratedFileType type() {
        return GeneratedFileType.DOCX;
    }

    /**
     * Generischer Einstieg ueber das neutrale Eingabemodell: Titel, Untertitel und Freitext werden unveraendert
     * an {@link #generate(String, String, String)} uebergeben.
     *
     * @throws IllegalArgumentException wenn {@code request} {@code null} ist
     */
    @Override
    public byte[] generate(FileContentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Kein Inhalt.");
        }
        return generate(request.title(), request.subtitle(), request.content());
    }

    /**
     * Baut ein {@code .docx} mit Titel, optionalem Untertitel und mehreren Textabsaetzen und gibt die
     * Datei als Byte-Array zurueck.
     *
     * @param title    Dokumenttitel (Pflicht; {@code null} wird als leer behandelt)
     * @param subtitle optionaler Untertitel ({@code null}/leer wird ausgelassen)
     * @param body     Freitext; Zeilenumbrueche ({@code \n}) werden zu einzelnen Absaetzen
     * @return die erzeugte, passive .docx-Datei als Byte-Array
     */
    public byte[] generate(String title, String subtitle, String body) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // In XML 1.0 unzulaessige Steuerzeichen entfernen, damit das OOXML-Dokument wohlgeformt/oeffenbar bleibt.
            String safeTitle = DocumentText.stripXmlIncompatibleChars(title);
            String safeSubtitle = DocumentText.stripXmlIncompatibleChars(subtitle);
            String safeBody = DocumentText.stripXmlIncompatibleChars(body);

            XWPFParagraph titleParagraph = document.createParagraph();
            titleParagraph.setAlignment(ParagraphAlignment.LEFT);
            XWPFRun titleRun = titleParagraph.createRun();
            titleRun.setBold(true);
            titleRun.setFontSize(18);
            titleRun.setText(safeTitle == null ? "" : safeTitle);

            if (safeSubtitle != null && !safeSubtitle.isBlank()) {
                XWPFParagraph subtitleParagraph = document.createParagraph();
                XWPFRun subtitleRun = subtitleParagraph.createRun();
                subtitleRun.setItalic(true);
                subtitleRun.setFontSize(13);
                subtitleRun.setText(safeSubtitle);
            }

            if (safeBody != null && !safeBody.isEmpty()) {
                // Zeilenweise: jede Zeile wird ein eigener Absatz (leere Zeilen bleiben als Leerabsatz erhalten).
                for (String line : safeBody.split("\n", -1)) {
                    XWPFParagraph paragraph = document.createParagraph();
                    XWPFRun run = paragraph.createRun();
                    // \r am Zeilenende (CRLF-Eingaben) entfernen; kein aktiver Inhalt, nur Text.
                    run.setText(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
                }
            }

            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            // POI/ByteArrayOutputStream werfen hier praktisch nie; defensiv umschliessen.
            throw new UncheckedIOException("DOCX konnte nicht erzeugt werden.", e);
        }
    }
}
