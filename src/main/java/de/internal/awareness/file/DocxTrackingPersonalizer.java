package de.internal.awareness.file;

import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Erzeugt eine individualisierte KOPIE eines bestehenden DOCX (im Speicher), in der ein sichtbarer,
 * anklickbarer Trainingslink als letzter Absatz ergaenzt wird.
 *
 * <p>Sicherheitsgrenze (verbindlich):</p>
 * <ul>
 *   <li>Die ORIGINAL-Datei der Bibliothek wird NICHT veraendert - hier wird ausschliesslich aus den
 *       uebergebenen Bytes eine neue Kopie gebaut und zurueckgegeben (das Eingabe-Array bleibt unberuehrt).</li>
 *   <li>Ergaenzt wird NUR ein normaler, sichtbarer Hyperlink (bewusster Klick des Empfaengers). KEIN
 *       Remote-Bild, KEIN Auto-Open, KEIN Makro, KEIN externes Template, KEINE automatische Netzwerkanfrage.
 *       Die einzige externe Relationship im Ergebnis ist genau dieser sichtbare Hyperlink.</li>
 * </ul>
 */
@Component
public class DocxTrackingPersonalizer {

    /** Sichtbarer Einleitungstext vor dem Link. */
    static final String LINK_LABEL = "Dokument / Informationen öffnen: ";

    /**
     * Baut aus {@code originalDocx} eine neue DOCX-Kopie mit angehaengtem sichtbaren Trainingslink.
     *
     * @param originalDocx die unveraenderten Original-Bytes (bleiben unveraendert)
     * @param url          der individuelle, bereits validierte Trainingslink
     * @return die individualisierte Kopie als neues Byte-Array
     */
    public byte[] withTrainingLink(byte[] originalDocx, String url) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(originalDocx));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun label = paragraph.createRun();
            label.setText(LINK_LABEL);

            // createHyperlinkRun legt eine (einzige) externe Relationship (TargetMode=External) auf die URL an;
            // der Link wird nur durch bewussten Klick aufgerufen - kein Auto-Open, kein Remote-Image.
            XWPFHyperlinkRun link = paragraph.createHyperlinkRun(url);
            link.setText(url);
            link.setUnderline(UnderlinePatterns.SINGLE);
            link.setColor("0000EE");

            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Individualisierte DOCX-Kopie konnte nicht erzeugt werden.", e);
        }
    }
}
