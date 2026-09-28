package de.internal.awareness.file;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Erzeugt PASSIVE {@code .pdf}-Dokumente mit Apache PDFBox.
 *
 * <p>Sicherheitsgrenze (verbindlich): Es entsteht ausschliesslich ein reines Text-PDF. Geschrieben werden NUR
 * Seiten mit Text-Operatoren im Content-Stream - daher gibt es</p>
 * <ul>
 *   <li>KEIN JavaScript, KEINE OpenAction und KEINE Zusatzaktionen ({@code /AA}), KEINE Launch/URI/GoToR/
 *       SubmitForm/ImportData-Aktionen,</li>
 *   <li>KEINE Annotationen/Links, KEINE Formulare (AcroForm/XFA), KEINEN Names-Baum,</li>
 *   <li>KEINE eingebetteten Dateien, KEINE Bilder, KEINE eingebetteten Schriften (nur die Standard-14-Schriften
 *       Helvetica/Helvetica-Bold/Helvetica-Oblique), KEINE externen Ressourcen, KEINE Verschluesselung.</li>
 * </ul>
 * In den Dokumentinformationen steht ausschliesslich der Titel (falls vorhanden); XMP-Metadaten werden nicht
 * geschrieben. Benutzertext landet nur als Text-Operand im (Flate-komprimierten) Content-Stream bzw. als
 * Hex-String im Titel - Zeichenketten wie {@code /JavaScript} bleiben dadurch reiner, sichtbarer Text und
 * erscheinen auch nicht als Schluesselwort in den Rohbytes der Datei.
 *
 * <p>Layout: A4, Rand 50 pt, Titel 18 pt fett, Untertitel 13 pt kursiv, Fliesstext 11 pt. Zeilen werden nach
 * gemessener Textbreite an Leerzeichen umbrochen; Woerter, die laenger als eine Zeile sind, werden hart
 * getrennt. Seitenumbrueche erfolgen automatisch, Leerzeilen bleiben als vertikaler Abstand erhalten, ein Tab
 * wird zu vier Leerzeichen.</p>
 *
 * <p>Zeichen, die die Standard-14-Schriften (WinAnsiEncoding) nicht darstellen koennen (z. B. Emoji, CJK),
 * werden durch {@code '?'} ersetzt - die Erzeugung wirft dafuer nie eine Exception. Deutsche Umlaute, {@code ß}
 * und {@code €} sind in WinAnsi enthalten und bleiben unveraendert.</p>
 *
 * <p>Die Klasse ist zustandslos und damit thread-safe: Dokument, Schriften und Layout-Zustand werden pro Aufruf
 * neu angelegt. Hinweis: PDFBox durchsucht beim ersten Anlegen einer Schrift pro JVM einmalig die
 * System-Schriften (fuer eine spaetere Bildschirmdarstellung) und legt dafuer einen Font-Cache an
 * ({@code .pdfbox.cache} im Benutzerverzeichnis bzw. im per {@code -Dpdfbox.fontcache} gesetzten Verzeichnis).
 * Das erzeugte PDF haengt davon nicht ab - es referenziert nur die Standard-14-Schriften per Name.</p>
 */
@Component
public class PdfGenerator implements FileContentGenerator {

    /** Seitenrand in Punkt (oben, unten, links, rechts). */
    static final float MARGIN = 50f;

    private static final float TITLE_FONT_SIZE = 18f;
    private static final float SUBTITLE_FONT_SIZE = 13f;
    private static final float BODY_FONT_SIZE = 11f;

    /** Zeilenabstand als Vielfaches der Schriftgroesse (bietet Platz fuer Ober- und Unterlaengen). */
    private static final float LINE_HEIGHT_FACTOR = 1.35f;

    /** Zusaetzlicher Abstand nach Titel bzw. Untertitel (Punkt). */
    private static final float SPACE_AFTER_TITLE = 6f;
    private static final float SPACE_AFTER_SUBTITLE = 10f;

    /** Ein Tab wird zu vier Leerzeichen (Standard-14-Schriften kennen kein Tab-Zeichen). */
    private static final String TAB_REPLACEMENT = "    ";

    /** Ersatz fuer Zeichen, die in WinAnsiEncoding nicht darstellbar sind. */
    private static final String UNENCODABLE_REPLACEMENT = "?";

    private static final int NO_BREAK_SPACE = 0x00A0;

    @Override
    public GeneratedFileType type() {
        return GeneratedFileType.PDF;
    }

    /**
     * Baut ein PDF mit optionalem Titel, optionalem Untertitel und optionalem Fliesstext und gibt die Datei als
     * Byte-Array zurueck. Leere/blanke Felder werden ausgelassen; ohne jeden Inhalt entsteht ein gueltiges PDF
     * mit einer leeren Seite.
     *
     * @param request Eingabe (Titel/Untertitel/Inhalt duerfen jeweils {@code null} oder leer sein)
     * @return die erzeugte, passive PDF-Datei als Byte-Array
     * @throws IllegalArgumentException wenn {@code request} {@code null} ist
     */
    @Override
    public byte[] generate(FileContentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Kein Inhalt.");
        }
        String title = presentText(request.title());
        String subtitle = presentText(request.subtitle());
        String content = presentText(request.content());

        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // Pro Dokument eigene Schrift-Instanzen: PDFont-Objekte gehoeren zum Dokument und sind nicht fuer
            // die gleichzeitige Nutzung in mehreren Dokumenten/Threads gedacht.
            TextStyle titleStyle = new TextStyle(
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), TITLE_FONT_SIZE);
            TextStyle subtitleStyle = new TextStyle(
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE), SUBTITLE_FONT_SIZE);
            TextStyle bodyStyle = new TextStyle(
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA), BODY_FONT_SIZE);

            try (PageLayout layout = new PageLayout(document)) {
                if (title != null) {
                    layout.writeBlock(title, titleStyle);
                    layout.addVerticalSpace(SPACE_AFTER_TITLE);
                }
                if (subtitle != null) {
                    layout.writeBlock(subtitle, subtitleStyle);
                    layout.addVerticalSpace(SPACE_AFTER_SUBTITLE);
                }
                if (content != null) {
                    layout.writeBlock(content, bodyStyle);
                }
            }

            if (title != null) {
                // Einziger Eintrag der Dokumentinformationen. Als Hex-String, damit Benutzertext (z. B.
                // "/OpenAction") nicht als Klartext-Schluesselwort in den Rohbytes steht. Unicode ist hier
                // erlaubt (PDFBox schreibt bei Bedarf UTF-16BE), daher keine '?'-Ersetzung noetig.
                String infoTitle = title.replaceAll("[\\t\\r\\n]+", " ").strip();
                document.getDocumentInformation().getCOSObject()
                        .setItem(COSName.TITLE, new COSString(infoTitle, true));
            }

            // Ohne Objekt-Streams (klassische Xref-Tabelle, PDF 1.4): maximal kompatibel und fuer
            // Sicherheitspruefungen transparent. Die Content-Streams selbst sind Flate-komprimiert.
            document.save(out, CompressParameters.NO_COMPRESSION);
            return out.toByteArray();
        } catch (IOException e) {
            // PDFBox/ByteArrayOutputStream werfen hier praktisch nie; defensiv umschliessen, ohne interne Details.
            throw new UncheckedIOException("PDF konnte nicht erzeugt werden.", e);
        }
    }

    /**
     * Bereinigt einen Eingabetext (XML-inkompatible Steuerzeichen entfernen wie bei DOCX, dann Unicode-NFC,
     * damit z. B. "u" + kombinierendes Trema als darstellbares "ü" ankommt). Gibt {@code null} zurueck, wenn
     * danach nichts Sichtbares uebrig bleibt (Feld gilt als nicht vorhanden).
     */
    private static String presentText(String raw) {
        String stripped = DocumentText.stripXmlIncompatibleChars(raw);
        if (stripped == null || stripped.isBlank()) {
            return null;
        }
        return Normalizer.normalize(stripped, Normalizer.Form.NFC);
    }

    /** Schrift + Groesse eines Textabschnitts inkl. Cache der nicht kodierbaren Zeichen (pro Aufruf). */
    private static final class TextStyle {

        private final PDFont font;
        private final float fontSize;
        private final float lineHeight;
        private final Set<Integer> unencodableCodePoints = new HashSet<>();

        TextStyle(PDFont font, float fontSize) {
            this.font = font;
            this.fontSize = fontSize;
            this.lineHeight = fontSize * LINE_HEIGHT_FACTOR;
        }

        /** Breite des (bereits kodierbaren) Texts in Punkt. */
        float width(String text) throws IOException {
            return font.getStringWidth(text) / 1000f * fontSize;
        }

        /**
         * Macht eine Zeile fuer die Schrift darstellbar: Tab -> vier Leerzeichen, uebriger Leerraum (z. B.
         * einzelnes CR, Unicode-Leerzeichen/-Zeilentrenner) -> Leerzeichen, nicht kodierbare Zeichen -> '?'.
         * Das geschuetzte Leerzeichen (U+00A0) ist in WinAnsi enthalten und bleibt erhalten (kein Umbruch).
         */
        String toEncodable(String line) {
            StringBuilder result = new StringBuilder(line.length());
            line.codePoints().forEach(codePoint -> {
                if (codePoint == '\t') {
                    result.append(TAB_REPLACEMENT);
                } else if (Character.isWhitespace(codePoint)
                        || (Character.isSpaceChar(codePoint) && codePoint != NO_BREAK_SPACE)) {
                    result.append(' ');
                } else if (isEncodable(codePoint)) {
                    result.appendCodePoint(codePoint);
                } else {
                    result.append(UNENCODABLE_REPLACEMENT);
                }
            });
            return result.toString();
        }

        private boolean isEncodable(int codePoint) {
            if (unencodableCodePoints.contains(codePoint)) {
                return false;
            }
            try {
                font.encode(new String(Character.toChars(codePoint)));
                return true;
            } catch (IllegalArgumentException | IOException e) {
                // Nicht in WinAnsiEncoding/der Schrift vorhanden - bewusst kein Logging von Benutzerinhalt.
                unencodableCodePoints.add(codePoint);
                return false;
            }
        }

        /**
         * Bricht eine (bereits kodierbare) Zeile nach gemessener Breite in Teilzeilen um. Umbruch an
         * Leerzeichen; Leerzeichen an der Umbruchstelle entfallen. Woerter, die allein breiter als eine Zeile
         * sind, werden zeichenweise hart getrennt. Eine leere Zeile ergibt genau eine leere Teilzeile.
         */
        List<String> wrap(String line, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            float currentWidth = 0f;
            for (String token : splitIntoSpaceAndWordRuns(line)) {
                float tokenWidth = width(token);
                if (currentWidth + tokenWidth <= maxWidth) {
                    current.append(token);
                    currentWidth += tokenWidth;
                    continue;
                }
                boolean isSpaceRun = token.charAt(0) == ' ';
                if (isSpaceRun) {
                    // Umbruchstelle: die Leerzeichen selbst werden nicht in die naechste Zeile uebernommen.
                    if (!current.isEmpty()) {
                        lines.add(stripTrailingSpaces(current));
                    }
                    current.setLength(0);
                    currentWidth = 0f;
                    continue;
                }
                if (!current.toString().isBlank()) {
                    lines.add(stripTrailingSpaces(current));
                }
                // Blanke Einrueckung vor einem nicht passenden Wort entfaellt (nur Leerraum, kein Textverlust).
                current.setLength(0);
                currentWidth = 0f;
                if (tokenWidth <= maxWidth) {
                    current.append(token);
                    currentWidth = tokenWidth;
                    continue;
                }
                // Hartes Trennen eines ueberlangen Worts, Zeichen fuer Zeichen.
                int offset = 0;
                while (offset < token.length()) {
                    int codePoint = token.codePointAt(offset);
                    String character = new String(Character.toChars(codePoint));
                    float characterWidth = width(character);
                    if (currentWidth + characterWidth > maxWidth && !current.isEmpty()) {
                        lines.add(current.toString());
                        current.setLength(0);
                        currentWidth = 0f;
                    }
                    current.append(character);
                    currentWidth += characterWidth;
                    offset += Character.charCount(codePoint);
                }
            }
            if (!current.isEmpty() || lines.isEmpty()) {
                lines.add(stripTrailingSpaces(current));
            }
            return lines;
        }

        private static String stripTrailingSpaces(StringBuilder text) {
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == ' ') {
                end--;
            }
            return text.substring(0, end);
        }

        /** Zerlegt eine Zeile in abwechselnde Folgen aus Leerzeichen bzw. Nicht-Leerzeichen. */
        private static List<String> splitIntoSpaceAndWordRuns(String line) {
            List<String> runs = new ArrayList<>();
            int start = 0;
            for (int i = 1; i <= line.length(); i++) {
                if (i == line.length() || (line.charAt(i) == ' ') != (line.charAt(start) == ' ')) {
                    runs.add(line.substring(start, i));
                    start = i;
                }
            }
            return runs;
        }
    }

    /**
     * Seiten- und Zeilenverwaltung fuer genau ein Dokument (pro Aufruf neu, daher ohne geteilten Zustand).
     * {@code cursorY} ist die Oberkante der naechsten Zeile in PDF-Koordinaten (Ursprung unten links).
     */
    private static final class PageLayout implements AutoCloseable {

        private final PDDocument document;
        private final float usableWidth = PDRectangle.A4.getWidth() - 2 * MARGIN;
        private final float top = PDRectangle.A4.getHeight() - MARGIN;
        private PDPageContentStream contentStream;
        private float cursorY;

        PageLayout(PDDocument document) {
            this.document = document;
        }

        /** Schreibt einen mehrzeiligen Textblock: Zeilen an "\n", je Zeile ein abschliessendes "\r" entfernt. */
        void writeBlock(String text, TextStyle style) throws IOException {
            for (String rawLine : text.split("\n", -1)) {
                String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
                for (String wrapped : style.wrap(style.toEncodable(line), usableWidth)) {
                    writeLine(wrapped, style);
                }
            }
        }

        void addVerticalSpace(float points) {
            cursorY -= points;
        }

        private void writeLine(String text, TextStyle style) throws IOException {
            boolean blank = text.isBlank();
            if (contentStream == null || (!blank && cursorY - style.lineHeight < MARGIN)) {
                // Leerzeilen erzwingen keinen Seitenumbruch (keine leeren Folgeseiten durch Leerzeilen am Ende).
                newPage();
            }
            if (!blank) {
                float baseline = cursorY - style.fontSize;
                contentStream.beginText();
                contentStream.setFont(style.font, style.fontSize);
                contentStream.newLineAtOffset(MARGIN, baseline);
                contentStream.showText(text);
                contentStream.endText();
            }
            cursorY -= style.lineHeight;
        }

        private void newPage() throws IOException {
            if (contentStream != null) {
                contentStream.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            contentStream = new PDPageContentStream(document, page);
            cursorY = top;
        }

        /** Schliesst den letzten Content-Stream; ohne jeden Inhalt entsteht genau eine leere Seite. */
        @Override
        public void close() throws IOException {
            if (contentStream == null) {
                newPage();
            }
            contentStream.close();
            contentStream = null;
        }
    }
}
