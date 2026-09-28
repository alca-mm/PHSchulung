package de.internal.awareness.file;

import org.springframework.stereotype.Component;

/**
 * Erzeugt fuer den Versand eine individualisierte KOPIE eines Anhangs (im Speicher) mit sichtbarem
 * Trainingslink - passend zum Dateityp. Das Original der Bibliothek wird nie veraendert; die Kopie wird
 * ausschliesslich fuer den einzelnen Versand verwendet und danach verworfen.
 *
 * <p>Personalisiert werden ausschliesslich die bestehenden Formate DOCX und XML. Fuer weitere passive
 * Dateitypen (z. B. PDF/XLSX/PPTX/TXT/CSV) findet bewusst KEINE Anhang-Personalisierung statt: Der Anhang
 * wird unveraendert versendet; der sichtbare Trainingslink steht wie bisher im Mail-Text. Dieses Verhalten
 * (und der Tracking-/Versandpfad) bleibt gegenueber dem bisherigen Stand unveraendert.</p>
 */
@Component
public class AttachmentPersonalizer {

    private final DocxTrackingPersonalizer docxPersonalizer;
    private final XmlTrackingPersonalizer xmlPersonalizer;

    public AttachmentPersonalizer(DocxTrackingPersonalizer docxPersonalizer,
                                  XmlTrackingPersonalizer xmlPersonalizer) {
        this.docxPersonalizer = docxPersonalizer;
        this.xmlPersonalizer = xmlPersonalizer;
    }

    /**
     * Liefert eine individualisierte Kopie der Anhangs-Bytes mit sichtbarem Trainingslink, je nach Dateityp
     * (DOCX: sichtbarer Hyperlink; XML: {@code <trainingLink>}-Textelement). Fuer alle uebrigen (neueren)
     * passiven Typen werden die Original-Bytes unveraendert zurueckgegeben (keine Anhang-Personalisierung).
     *
     * @param type     der Dateityp des Anhangs
     * @param original die unveraenderten Original-Bytes
     * @param url      der individuelle, bereits validierte Trainingslink
     * @return die individualisierte Kopie als neues Byte-Array (bzw. das Original ohne Aenderung)
     */
    public byte[] personalize(GeneratedFileType type, byte[] original, String url) {
        return switch (type) {
            case DOCX -> docxPersonalizer.withTrainingLink(original, url);
            case XML -> xmlPersonalizer.withTrainingLink(original, url);
            default -> original;
        };
    }
}
