package de.internal.awareness.file;

import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackageRelationshipTypes;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextCharacterProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextParagraph;
import org.openxmlformats.schemas.officeDocument.x2006.extendedProperties.CTProperties;
import org.springframework.stereotype.Component;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Erzeugt PASSIVE {@code .pptx}-Praesentationen (Office Open XML) mit Apache POI.
 *
 * <p>Aufbau: Folie 1 ist eine Titelfolie (Layout "Titelfolie" des Standard-Masters) mit Titel und optionalem
 * Untertitel. Der Freitext-Inhalt folgt zeilenweise auf Inhaltsfolien (Layout "Titel und Inhalt"); jede
 * Eingabezeile wird ein eigener Absatz, leere Zeilen bleiben als leere Absaetze erhalten. Langer Inhalt wird
 * auf mehrere Folien verteilt (hoechstens {@value #MAX_LINES_PER_SLIDE} Zeilen und
 * {@value #MAX_CHARS_PER_SLIDE} Zeichen pro Folie, zusaetzlich eine vorsichtige Schaetzung der umbrochenen
 * Bildschirmzeilen, damit der Text in den Inhaltsrahmen passt). Folgefolien tragen den Zusatz
 * "{@value #CONTINUATION_SUFFIX}". Einzelne Zeilen, die laenger als das Zeichenbudget sind, werden - moeglichst an
 * einem Leerzeichen - geteilt. Es geht dabei KEIN Text verloren: Die Teilstuecke ergeben aneinandergereiht
 * exakt die Originalzeile.</p>
 *
 * <p>Sicherheitsgrenze (verbindlich): Es entstehen ausschliesslich normale, ungefaehrliche Praesentationen.
 * Grundlage ist die in POI eingebaute leere Vorlage ({@code new XMLSlideShow()}); es wird NIE eine Vorlage aus
 * einer Datei oder URL geladen. Diese Vorlage enthaelt nur Master, Layouts, Theme und Eigenschaften - keine
 * Makros, keine eingebetteten Objekte, keine externen Relationships. Ihr Vorschaubild
 * ({@code docProps/thumbnail.jpeg}) wird entfernt, damit das Paket gar keine Bilddaten enthaelt. Geschrieben
 * werden NUR Textabsaetze und Textlaeufe - daher gibt es</p>
 * <ul>
 *   <li>KEINE Makros/VBA (Format {@code .pptx}, nicht {@code .pptm}; keine {@code vbaProject.bin}),</li>
 *   <li>KEINE eingebetteten Objekte/OLE/ActiveX, KEINE Bilder, Audio- oder Videodateien,</li>
 *   <li>KEINE Hyperlinks und KEINE Klick-/Mouseover-Aktionen (URLs im Text bleiben reiner Text),</li>
 *   <li>KEINE externen Relationships/Templates und damit KEINE automatischen Netzwerkzugriffe.</li>
 * </ul>
 *
 * <p>Dokumenteigenschaften: Die veralteten Vorlagenwerte (u. a. "Microsoft Office PowerPoint", Daten aus
 * 2006/2011) werden ersetzt - Anwendung {@value #APPLICATION_NAME}, echte Folienanzahl, Erstellungs-/
 * Aenderungszeitpunkt = Erzeugungszeitpunkt; keine Benutzer-, Rechner- oder Firmenangaben.</p>
 *
 * <p>Die Klasse ist zustandslos und damit thread-sicher; jeder Aufruf arbeitet auf einer eigenen
 * Praesentation. Benutzerinhalte werden nicht geloggt.</p>
 */
@Component
public class PptxGenerator implements FileContentGenerator {

    /** Hoechstzahl an Inhaltszeilen (Absaetzen) pro Inhaltsfolie. */
    static final int MAX_LINES_PER_SLIDE = 15;

    /** Hoechstzahl an Zeichen pro Inhaltsfolie; laengere Einzelzeilen werden in Teilstuecke geteilt. */
    static final int MAX_CHARS_PER_SLIDE = 1_500;

    /**
     * Vorsichtig geschaetzte Zeichen pro umbrochener Bildschirmzeile bei {@link #BODY_FONT_SIZE} im
     * Inhaltsrahmen (eher zu wenig als zu viel, damit der Text sicher in den Rahmen passt).
     */
    static final int ESTIMATED_CHARS_PER_ROW = 95;

    /** Hoechstzahl geschaetzter Bildschirmzeilen (inklusive automatischer Umbrueche) pro Inhaltsfolie. */
    static final int MAX_ROWS_PER_SLIDE = 20;

    /** Schriftgroesse (pt) des Inhaltstextes; so passen {@link #MAX_ROWS_PER_SLIDE} Zeilen in den Rahmen. */
    static final double BODY_FONT_SIZE = 12.0;

    /** Folientitel der Inhaltsfolien, wenn kein Dokumenttitel angegeben ist. */
    static final String DEFAULT_CONTENT_TITLE = "Inhalt";

    /** Zusatz fuer den Titel von Folgefolien desselben Inhalts. */
    static final String CONTINUATION_SUFFIX = " (Fortsetzung)";

    /** Anwendungs-/Erstellerangabe in den Dokumenteigenschaften (wie bei den DOCX/XLSX-Dateien aus POI). */
    static final String APPLICATION_NAME = "Apache POI";

    /** Rand (pt) fuer die Ersatz-Textfelder, falls ein Layout fehlt. */
    private static final double MARGIN = 36.0;

    @Override
    public GeneratedFileType type() {
        return GeneratedFileType.PPTX;
    }

    /**
     * Baut die Praesentation aus Titel, optionalem Untertitel und Freitext und gibt sie als Byte-Array zurueck.
     * Leere, nur aus Leerraum bestehende oder {@code null}-Felder gelten als nicht vorhanden. Ohne Inhalt
     * entsteht nur die Titelfolie; ohne jede Angabe eine einzelne leere Folie.
     *
     * @param request neutrales Eingabemodell (Pflicht)
     * @return die erzeugte, passive .pptx-Datei als Byte-Array
     * @throws IllegalArgumentException wenn {@code request} {@code null} ist
     */
    @Override
    public byte[] generate(FileContentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Kein Inhalt.");
        }
        // In XML 1.0 unzulaessige Steuerzeichen entfernen, damit das OOXML-Paket wohlgeformt/oeffenbar bleibt.
        String title = presentOrNull(request.title());
        String subtitle = presentOrNull(request.subtitle());
        String content = presentOrNull(request.content());
        List<List<String>> pages = content == null ? List.of() : paginate(splitLines(content));

        try (XMLSlideShow slideShow = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            removeThumbnail(slideShow);
            addTitleSlide(slideShow, title, subtitle);

            String heading = title != null ? title : DEFAULT_CONTENT_TITLE;
            for (int i = 0; i < pages.size(); i++) {
                addContentSlide(slideShow, i == 0 ? heading : heading + CONTINUATION_SUFFIX, pages.get(i));
            }

            writeMetadata(slideShow, title);

            slideShow.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            // POI/ByteArrayOutputStream werfen hier praktisch nie; defensiv umschliessen.
            throw new UncheckedIOException("PPTX konnte nicht erzeugt werden.", e);
        }
    }

    /**
     * Sucht das Folienlayout des gewuenschten Typs im Standard-Master (erster Master der eingebauten Vorlage).
     * Paket-sichtbar, damit Tests den Rueckfall auf einfache Textfelder ohne passendes Layout pruefen koennen.
     *
     * @return das Layout oder {@code null}, wenn es nicht existiert
     */
    XSLFSlideLayout findLayout(XMLSlideShow slideShow, SlideLayout type) {
        List<XSLFSlideMaster> masters = slideShow.getSlideMasters();
        return masters.isEmpty() ? null : masters.get(0).getLayout(type);
    }

    // ------------------------------------------------------------------------------------------------
    // Folien
    // ------------------------------------------------------------------------------------------------

    /**
     * Titelfolie: Titel in den (zentrierten) Titelplatzhalter, Untertitel in den Untertitelplatzhalter. Alle
     * Platzhalter, die leer blieben, werden entfernt - so erscheinen keine "Klicken Sie hier..."-Rahmen.
     */
    private void addTitleSlide(XMLSlideShow slideShow, String title, String subtitle) {
        XSLFSlide slide = createSlide(slideShow, SlideLayout.TITLE);

        XSLFTextShape titleShape = null;
        XSLFTextShape subtitleShape = null;
        for (XSLFTextShape placeholder : slide.getPlaceholders()) {
            Placeholder type = placeholder.getTextType();
            if (title != null && titleShape == null && isTitle(type)) {
                titleShape = placeholder;
            } else if (subtitle != null && subtitleShape == null && type == Placeholder.SUBTITLE) {
                subtitleShape = placeholder;
            } else {
                slide.removeShape(placeholder);
            }
        }

        Dimension page = slideShow.getPageSize();
        if (title != null) {
            if (titleShape == null) {
                titleShape = createTextBox(slide, new Rectangle2D.Double(
                        MARGIN, page.getHeight() * 0.28, page.getWidth() - 2 * MARGIN, 110));
            }
            writeParagraphs(titleShape, splitLines(title), titleFontSize(title), TextAlign.CENTER, false);
        }
        if (subtitle != null) {
            if (subtitleShape == null) {
                subtitleShape = createTextBox(slide, new Rectangle2D.Double(
                        2 * MARGIN, page.getHeight() * 0.28 + 120, page.getWidth() - 4 * MARGIN, 130));
            }
            writeParagraphs(subtitleShape, splitLines(subtitle), subtitleFontSize(subtitle), TextAlign.CENTER, false);
        }
    }

    /**
     * Inhaltsfolie: Folientitel in den Titelplatzhalter, die Zeilen als Absaetze in den Inhaltsplatzhalter
     * (ohne Aufzaehlungszeichen, einheitliche Schriftgroesse). Uebrige Platzhalter werden entfernt.
     */
    private void addContentSlide(XMLSlideShow slideShow, String heading, List<String> lines) {
        XSLFSlide slide = createSlide(slideShow, SlideLayout.TITLE_AND_CONTENT);

        XSLFTextShape titleShape = null;
        XSLFTextShape bodyShape = null;
        for (XSLFTextShape placeholder : slide.getPlaceholders()) {
            Placeholder type = placeholder.getTextType();
            if (titleShape == null && isTitle(type)) {
                titleShape = placeholder;
            } else if (bodyShape == null && (type == Placeholder.BODY || type == Placeholder.CONTENT)) {
                bodyShape = placeholder;
            } else {
                slide.removeShape(placeholder);
            }
        }

        Dimension page = slideShow.getPageSize();
        if (titleShape == null) {
            titleShape = createTextBox(slide, new Rectangle2D.Double(
                    MARGIN, 20, page.getWidth() - 2 * MARGIN, 80));
        }
        if (bodyShape == null) {
            bodyShape = createTextBox(slide, new Rectangle2D.Double(
                    MARGIN, 110, page.getWidth() - 2 * MARGIN, page.getHeight() - 110 - MARGIN));
        }
        writeParagraphs(titleShape, splitLines(heading), titleFontSize(heading), null, false);
        writeParagraphs(bodyShape, lines, BODY_FONT_SIZE, null, true);
    }

    /**
     * Legt eine Folie mit dem gewuenschten Layout an; fehlt das Layout, entsteht eine einfache Folie
     * ({@code createSlide()}), die anschliessend mit einfachen Textfeldern befuellt wird.
     */
    private XSLFSlide createSlide(XMLSlideShow slideShow, SlideLayout type) {
        XSLFSlideLayout layout = findLayout(slideShow, type);
        return layout != null ? slideShow.createSlide(layout) : slideShow.createSlide();
    }

    private static boolean isTitle(Placeholder type) {
        return type == Placeholder.TITLE || type == Placeholder.CENTERED_TITLE;
    }

    /** Einfaches Textfeld (Ersatz fuer einen fehlenden Platzhalter) mit Zeilenumbruch. */
    private static XSLFTextShape createTextBox(XSLFSlide slide, Rectangle2D anchor) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(anchor);
        box.setWordWrap(true);
        return box;
    }

    /**
     * Ersetzt den Text eines Shapes durch die gegebenen Zeilen (je Zeile ein Absatz). Leere Zeilen werden zu
     * leeren Absaetzen. Der Text wird ausschliesslich als Textlauf gesetzt und von POI korrekt in OOXML
     * kodiert - es entstehen weder Felder noch Hyperlinks.
     *
     * @param fontSize   Schriftgroesse in pt oder {@code null} fuer die Vorgabe des Layouts
     * @param align      Absatzausrichtung oder {@code null} fuer die Vorgabe des Layouts
     * @param plainText  {@code true}: ohne Aufzaehlungszeichen und ohne Einzug (Fliesstext)
     */
    private static void writeParagraphs(XSLFTextShape shape, List<String> lines, Double fontSize,
                                        TextAlign align, boolean plainText) {
        shape.clearText();
        for (String line : lines) {
            XSLFTextParagraph paragraph = shape.addNewTextParagraph();
            if (align != null) {
                paragraph.setTextAlign(align);
            }
            if (plainText) {
                paragraph.setBullet(false);
                paragraph.setLeftMargin(0.0);
                paragraph.setIndent(0.0);
            }
            if (!line.isEmpty()) {
                XSLFTextRun run = paragraph.addNewTextRun();
                run.setText(line);
                if (fontSize != null) {
                    run.setFontSize(fontSize);
                }
            }
            if (fontSize != null) {
                // Auch das Absatzende bekommt die Schriftgroesse, damit leere Zeilen nicht hoeher werden.
                CTTextParagraph ctParagraph = paragraph.getXmlObject();
                CTTextCharacterProperties endProps = ctParagraph.isSetEndParaRPr()
                        ? ctParagraph.getEndParaRPr() : ctParagraph.addNewEndParaRPr();
                endProps.setSz((int) Math.round(fontSize * 100));
            }
        }
    }

    /** Schriftgroesse fuer Titel: Lange Titel (bis 255 Zeichen) werden kleiner gesetzt, damit sie passen. */
    private static Double titleFontSize(String text) {
        int length = text.length();
        if (length <= 25) {
            return null;
        }
        if (length <= 70) {
            return 28.0;
        }
        return length <= 150 ? 20.0 : 16.0;
    }

    /** Schriftgroesse fuer den Untertitel: Lange Untertitel werden kleiner gesetzt, damit sie passen. */
    private static Double subtitleFontSize(String text) {
        int length = text.length();
        if (length <= 60) {
            return null;
        }
        return length <= 120 ? 20.0 : 14.0;
    }

    // ------------------------------------------------------------------------------------------------
    // Paket
    // ------------------------------------------------------------------------------------------------

    /**
     * Setzt ehrliche, datensparsame Dokumenteigenschaften (konsistent mit DOCX/XLSX aus Apache POI).
     *
     * <p>Die eingebaute POI-Vorlage bringt veraltete Werte mit (Anwendung "Microsoft Office PowerPoint",
     * AppVersion, Erstellung 2006/Aenderung 2011, Folien-/Wortstatistik 0, "Office Theme"). Diese werden
     * vollstaendig ersetzt: Die erweiterten Eigenschaften ({@code docProps/app.xml}) enthalten nur noch
     * {@value #APPLICATION_NAME} als Anwendung und die tatsaechliche Folienanzahl; Erstellungs- und
     * Aenderungszeitpunkt sind der Erzeugungszeitpunkt. Es werden bewusst KEINE Benutzer-, Rechner- oder
     * Firmenangaben geschrieben (kein lastModifiedBy, keine Company/Manager/Template-Angabe).</p>
     */
    private static void writeMetadata(XMLSlideShow slideShow, String title) {
        POIXMLProperties properties = slideShow.getProperties();

        POIXMLProperties.CoreProperties core = properties.getCoreProperties();
        Date now = new Date();
        core.setCreated(Optional.of(now));
        core.setModified(Optional.of(now));
        core.setCreator(APPLICATION_NAME);
        core.getUnderlyingProperties().setRevisionProperty(Optional.empty());
        core.getUnderlyingProperties().setLastModifiedByProperty(Optional.empty());
        if (title != null) {
            core.setTitle(title);
        }

        CTProperties extended = properties.getExtendedProperties().getUnderlyingProperties();
        // Alle Vorlagenwerte verwerfen (leeres Properties-Element) und nur zutreffende Angaben setzen.
        extended.set(CTProperties.Factory.newInstance());
        extended.setApplication(APPLICATION_NAME);
        extended.setSlides(slideShow.getSlides().size());
    }

    /**
     * Entfernt das Vorschaubild ({@code docProps/thumbnail.jpeg}) der eingebauten Vorlage samt Relationship.
     * Es ist harmlos, zeigt aber eine leere Folie und waere die einzige Bilddatei im Paket - ohne es enthaelt
     * die Praesentation ueberhaupt keine Binaer-/Bilddaten.
     */
    private static void removeThumbnail(XMLSlideShow slideShow) {
        OPCPackage pkg = slideShow.getPackage();
        for (PackagePart part : pkg.getPartsByRelationshipType(PackageRelationshipTypes.THUMBNAIL)) {
            pkg.removePart(part);
        }
        List<String> relationshipIds = new ArrayList<>();
        for (PackageRelationship relationship : pkg.getRelationshipsByType(PackageRelationshipTypes.THUMBNAIL)) {
            relationshipIds.add(relationship.getId());
        }
        relationshipIds.forEach(pkg::removeRelationship);
    }

    // ------------------------------------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------------------------------------

    /** Bereinigt den Text (XML-inkompatible Zeichen) und liefert {@code null}, wenn nichts Sichtbares bleibt. */
    private static String presentOrNull(String value) {
        String cleaned = DocumentText.stripXmlIncompatibleChars(value);
        return cleaned == null || cleaned.isBlank() ? null : cleaned;
    }

    /** Teilt an {@code \n} (leere Zeilen bleiben erhalten) und entfernt je Zeile ein abschliessendes {@code \r}. */
    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        return lines;
    }

    /**
     * Verteilt die Zeilen auf Folien. Eine neue Folie beginnt, sobald die naechste Zeile das Zeilen-, Zeichen-
     * oder Bildschirmzeilen-Budget der aktuellen Folie ueberschreiten wuerde. Ueberlange Zeilen werden zuvor
     * geteilt, sodass jedes Stueck allein auf eine Folie passt. Reihenfolge und Inhalt bleiben vollstaendig.
     */
    static List<List<String>> paginate(List<String> lines) {
        List<List<String>> pages = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int chars = 0;
        int rows = 0;
        for (String line : lines) {
            for (String piece : splitOverlongLine(line)) {
                int pieceRows = estimatedRows(piece);
                boolean full = current.size() >= MAX_LINES_PER_SLIDE
                        || chars + piece.length() > MAX_CHARS_PER_SLIDE
                        || rows + pieceRows > MAX_ROWS_PER_SLIDE;
                if (full && !current.isEmpty()) {
                    pages.add(current);
                    current = new ArrayList<>();
                    chars = 0;
                    rows = 0;
                }
                current.add(piece);
                chars += piece.length();
                rows += pieceRows;
            }
        }
        if (!current.isEmpty()) {
            pages.add(current);
        }
        return pages;
    }

    /** Geschaetzte Anzahl umbrochener Bildschirmzeilen einer Zeile (mindestens 1, auch fuer Leerzeilen). */
    private static int estimatedRows(String line) {
        return Math.max(1, (line.length() + ESTIMATED_CHARS_PER_ROW - 1) / ESTIMATED_CHARS_PER_ROW);
    }

    /**
     * Teilt eine Zeile, die laenger als {@link #MAX_CHARS_PER_SLIDE} ist, in Stuecke von hoechstens dieser
     * Laenge. Bevorzugt wird nach dem letzten Leerraum in der zweiten Haelfte des Stuecks geteilt, sonst hart.
     * Ersatzzeichenpaare (z. B. Emojis) werden nie zerrissen. Die Stuecke ergeben aneinandergereiht exakt die
     * Originalzeile.
     */
    static List<String> splitOverlongLine(String line) {
        if (line.length() <= MAX_CHARS_PER_SLIDE) {
            return List.of(line);
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (line.length() - start > MAX_CHARS_PER_SLIDE) {
            int end = start + MAX_CHARS_PER_SLIDE;
            int cut = end;
            for (int i = end - 1; i > start + MAX_CHARS_PER_SLIDE / 2; i--) {
                if (Character.isWhitespace(line.charAt(i))) {
                    cut = i + 1;
                    break;
                }
            }
            if (Character.isHighSurrogate(line.charAt(cut - 1))) {
                cut--;
            }
            pieces.add(line.substring(start, cut));
            start = cut;
        }
        pieces.add(line.substring(start));
        return pieces;
    }
}
