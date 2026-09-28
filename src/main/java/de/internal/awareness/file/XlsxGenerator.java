package de.internal.awareness.file;

import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.regex.Pattern;

/**
 * Erzeugt PASSIVE {@code .xlsx}-Arbeitsmappen (Office Open XML / SpreadsheetML) mit Apache POI.
 *
 * <p>Aufbau (genau ein Tabellenblatt mit dem FESTEN Namen {@value #SHEET_NAME} - nie aus Benutzereingaben):</p>
 * <ol>
 *   <li>Zeile 0: Titel (fett), falls vorhanden,</li>
 *   <li>naechste Zeile: Untertitel (kursiv), falls vorhanden,</li>
 *   <li>eine Leerzeile, falls ein Kopfbereich geschrieben wurde UND Inhalt folgt,</li>
 *   <li>danach eine Tabellenzeile pro Inhaltszeile. Jede Zeile wird an Tabulatoren ({@code \t}) in
 *       aufeinanderfolgende Spalten aufgeteilt - eine aus Excel kopierte Tabelle behaelt so ihre Spalten.
 *       Eine Zeile ohne Tabulator landet vollstaendig in Spalte A, eine leere Zeile ergibt eine leere
 *       Tabellenzeile. Leere Segmente (z. B. {@code "a\t\tb"}) erzeugen keine Zelle, verschieben aber die
 *       Spaltenposition.</li>
 * </ol>
 *
 * <p>Sicherheitsgrenze (verbindlich): Es entsteht ausschliesslich eine normale, ungefaehrliche Tabelle.
 * Geschrieben werden NUR Textzellen - daher gibt es</p>
 * <ul>
 *   <li>KEINE Makros/VBA (das Format ist {@code .xlsx}, nicht {@code .xlsm}; es wird keine vbaProject.bin
 *       eingebettet),</li>
 *   <li>KEINE Formeln: Jede Zelle wird ausschliesslich ueber {@code setCellValue(String)} als Text gesetzt,
 *       NIE ueber {@code setCellFormula}. Formelartige Eingaben wie {@code =1+1}, {@code +1}, {@code -1},
 *       {@code @SUM(A1)}, {@code =HYPERLINK(...)} oder DDE-Muster ({@code =cmd|' /C calc'!A0}) werden
 *       WOERTLICH als String gespeichert (kein {@code <f>}-Element, kein in den Wert eingefuegtes
 *       Apostroph),</li>
 *   <li>das OOXML-Escape-Muster {@code _xHHHH_} wird maskiert ({@code _x005F_}), damit Excel/POI solche
 *       Folgen beim Lesen NICHT in andere Zeichen (z. B. Steuerzeichen oder {@code =}) umwandeln - der Text
 *       bleibt auch hier woertlich erhalten (siehe {@link #escapeXstring(String)}),</li>
 *   <li>zusaetzlich traegt jede Zelle das Zahlenformat {@code "@"} (Text) und das Quote-Prefix-Flag, damit
 *       Excel den Inhalt auch nach spaeterem Bearbeiten weiterhin als Text und nicht als Formel auswertet,</li>
 *   <li>KEINE externen Arbeitsmappen-Links, KEINE Datenverbindungen/Abfragetabellen, KEINE Hyperlinks,
 *       KEINE eingebetteten Objekte/OLE/ActiveX, KEINE Pivot-Caches, KEINE externen Relationships.</li>
 * </ul>
 *
 * <p>Bewusst kein {@code autoSizeColumn} (benoetigt AWT/Schriftmetriken und ist serverseitig unzuverlaessig);
 * Spalte A erhaelt stattdessen eine feste Breite. Eingabegrenzen (Titel/Untertitel &lt;= 255, Inhalt
 * &lt;= 10.000 Zeichen) werden im Formular geprueft; innerhalb dieser Grenzen bleiben Zeilen-, Spalten- und
 * Zelltextgrenzen von Excel weit unterschritten (auch nach der {@code _x005F_}-Maskierung, die einen Wert
 * hoechstens knapp verdoppelt), der gesamte Text landet in der Datei.</p>
 *
 * <p>Die Klasse ist zustandslos und damit thread-sicher; jede Erzeugung arbeitet mit einer eigenen
 * Arbeitsmappe. Benutzerinhalte werden nicht protokolliert.</p>
 */
@Component
public class XlsxGenerator implements FileContentGenerator {

    /** Fester, nicht benutzerkontrollierter Name des einzigen Tabellenblatts. */
    static final String SHEET_NAME = "Inhalt";

    /** Excel-Zahlenformat "Text" (eingebautes Format 0x31). */
    private static final String TEXT_FORMAT = "@";

    /** Feste Breite fuer Spalte A in 1/256 Zeichenbreiten (kein autoSize, siehe Klassendoku). */
    private static final int FIRST_COLUMN_WIDTH = 80 * 256;

    /**
     * Findet den Unterstrich, mit dem eine OOXML-Escape-Folge {@code _xHHHH_} beginnt (x klein oder gross,
     * Hex-Ziffern beliebiger Schreibweise). Vorberechnet, da {@link Pattern} unveraenderlich/thread-sicher ist.
     */
    private static final Pattern XSTRING_ESCAPE_START = Pattern.compile("_(?=[xX][0-9A-Fa-f]{4}_)");

    public XlsxGenerator() {
        // Zustandslos: keine Abhaengigkeiten, keine Felder mit veraenderlichem Zustand.
    }

    @Override
    public GeneratedFileType type() {
        return GeneratedFileType.XLSX;
    }

    /**
     * Baut eine {@code .xlsx}-Arbeitsmappe aus Titel, optionalem Untertitel und Inhalt und gibt sie als
     * Byte-Array zurueck. {@code null}/leere/nur aus Leerraum bestehende Felder werden als "nicht vorhanden"
     * behandelt; das Ergebnis ist dann eine gueltige Arbeitsmappe mit (ggf. leerem) Blatt {@value #SHEET_NAME}.
     *
     * @param request Eingabe (Pflicht)
     * @return die erzeugte, passive .xlsx-Datei als Byte-Array
     * @throws IllegalArgumentException bei {@code request == null}
     * @throws UncheckedIOException     falls das Schreiben der Arbeitsmappe fehlschlaegt
     */
    @Override
    public byte[] generate(FileContentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Kein Inhalt.");
        }

        // In XML 1.0 unzulaessige Steuerzeichen entfernen, damit das OOXML-Paket wohlgeformt/oeffenbar bleibt.
        String title = DocumentText.stripXmlIncompatibleChars(request.title());
        String subtitle = DocumentText.stripXmlIncompatibleChars(request.subtitle());
        String content = DocumentText.stripXmlIncompatibleChars(request.content());

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            XSSFSheet sheet = workbook.createSheet(SHEET_NAME);
            sheet.setColumnWidth(0, FIRST_COLUMN_WIDTH);

            XSSFCellStyle titleStyle = textStyle(workbook, true, false);
            XSSFCellStyle subtitleStyle = textStyle(workbook, false, true);
            XSSFCellStyle bodyStyle = textStyle(workbook, false, false);

            int rowIndex = 0;
            if (isPresent(title)) {
                writeTextCell(sheet, rowIndex++, 0, title, titleStyle);
                // Unkritische Metadaten (docProps/core.xml); POI kodiert den Wert als XML-Text. Bewusst OHNE
                // _x005F_-Maskierung: core.xml ist kein ST_Xstring, der Titel wird dort nicht dekodiert.
                workbook.getProperties().getCoreProperties().setTitle(title);
            }
            if (isPresent(subtitle)) {
                writeTextCell(sheet, rowIndex++, 0, subtitle, subtitleStyle);
            }

            if (isPresent(content)) {
                if (rowIndex > 0) {
                    // Eine Leerzeile trennt den Kopfbereich optisch vom Inhalt (Zeile wird nicht angelegt).
                    rowIndex++;
                }
                for (String rawLine : content.split("\n", -1)) {
                    // Genau ein \r am Zeilenende (CRLF-Eingaben) entfernen.
                    String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
                    writeLine(sheet, rowIndex++, line, bodyStyle);
                }
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            // POI/ByteArrayOutputStream werfen hier praktisch nie; defensiv umschliessen.
            throw new UncheckedIOException("XLSX konnte nicht erzeugt werden.", e);
        }
    }

    /** Titel/Untertitel/Inhalt gelten nur als vorhanden, wenn sie nicht {@code null} und nicht leer/blank sind. */
    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Schreibt eine Inhaltszeile: Aufteilung an Tabulatoren in aufeinanderfolgende Spalten. Leere Segmente
     * (auch eine komplett leere Zeile) erzeugen keine Zelle - die Tabellenzeile bleibt dann leer.
     */
    private static void writeLine(XSSFSheet sheet, int rowIndex, String line, XSSFCellStyle style) {
        String[] segments = line.split("\t", -1);
        for (int column = 0; column < segments.length; column++) {
            if (!segments[column].isEmpty()) {
                writeTextCell(sheet, rowIndex, column, segments[column], style);
            }
        }
    }

    /**
     * Setzt genau eine Textzelle. Ausschliesslich {@code setCellValue(String)} - dadurch ist der Zelltyp
     * immer {@code STRING} und der Wert wird unveraendert gespeichert, auch wenn er wie eine Formel aussieht.
     * Vorher wird das OOXML-Escape-Muster {@code _xHHHH_} maskiert (siehe {@link #escapeXstring(String)}).
     * Die Tabellenzeile wird erst bei Bedarf angelegt (Leerzeilen erzeugen kein {@code <row>}-Element).
     */
    private static void writeTextCell(XSSFSheet sheet, int rowIndex, int column, String value,
                                      XSSFCellStyle style) {
        XSSFRow row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }
        XSSFCell cell = row.createCell(column);
        cell.setCellValue(escapeXstring(value));
        cell.setCellStyle(style);
    }

    /**
     * Maskiert das SpreadsheetML-Escape-Muster {@code _xHHHH_} (Typ ST_Xstring), damit der Text WOERTLICH
     * erhalten bleibt.
     *
     * <p>Hintergrund: Excel und POI dekodieren beim Lesen jede Folge {@code _xHHHH_} (vier Hex-Ziffern) in das
     * entsprechende Zeichen - aus {@code _x0041_} wuerde "A", aus {@code _x0007_} ein Steuerzeichen und aus
     * {@code _x003D_1+1} die Anzeige "=1+1" (weiterhin nur Text, aber nicht mehr woertlich). POI 5.3.0
     * maskiert beim Schreiben nicht selbst. Deshalb wird der fuehrende Unterstrich jedes Vorkommens selbst als
     * {@code _x005F_} kodiert: {@code _x0041_} wird zu {@code _x005F_x0041_} und liest sich wieder exakt als
     * {@code _x0041_}.</p>
     *
     * <p>POI dekodiert nur ein kleines {@code x}; ein grosses {@code X} wird vorsorglich ebenfalls maskiert.
     * Das ist verlustfrei: Ein Leser, der {@code _X...} nicht dekodiert, liest {@code _x005F_} als "_" und den
     * Rest unveraendert - das Ergebnis ist in beiden Faellen der Originaltext.</p>
     *
     * <p>Nur fuer Zellwerte. Die Dokumenteigenschaft Titel ({@code docProps/core.xml}) ist kein ST_Xstring und
     * wird unveraendert gesetzt.</p>
     */
    static String escapeXstring(String value) {
        return XSTRING_ESCAPE_START.matcher(value).replaceAll("_x005F_");
    }

    /**
     * Zellstil fuer Text: Zahlenformat {@code "@"} (Text) plus Quote-Prefix, damit Excel die Zelle auch beim
     * spaeteren Bearbeiten nicht als Formel/Zahl interpretiert. Optional fett bzw. kursiv.
     */
    private static XSSFCellStyle textStyle(XSSFWorkbook workbook, boolean bold, boolean italic) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat(TEXT_FORMAT));
        style.setQuotePrefixed(true);
        if (bold || italic) {
            XSSFFont font = workbook.createFont();
            font.setBold(bold);
            font.setItalic(italic);
            style.setFont(font);
        }
        return style;
    }
}
