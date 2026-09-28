package de.internal.awareness.file;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sicherheits- und Korrektheitstests fuer {@link XlsxGenerator}: gueltiges Office-Open-XML (SpreadsheetML),
 * feste Blattstruktur, exakte Zellwerte/-positionen, JEDE Zelle als Text (auch formelartige Eingaben werden
 * woertlich als String gespeichert - keine Formel), sowie ZIP-Ebene: KEINE Makros, KEINE externen Links,
 * KEINE Datenverbindungen, KEINE eingebetteten Objekte, KEINE externen Relationships.
 */
class XlsxGeneratorTest {

    private final XlsxGenerator generator = new XlsxGenerator();

    // ------------------------------------------------------------------ Hilfsfunktionen

    private static XSSFWorkbook open(byte[] xlsx) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(xlsx));
    }

    /** Liest alle ZIP-Eintraege (Name -> Inhalt als UTF-8). */
    private static Map<String, String> zipEntries(byte[] xlsx) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /** Wert einer Zelle als String oder {@code null}, wenn Zeile/Zelle nicht existiert. */
    private static String value(XSSFSheet sheet, int row, int col) {
        Row r = sheet.getRow(row);
        if (r == null) {
            return null;
        }
        Cell c = r.getCell(col);
        return c == null ? null : c.getStringCellValue();
    }

    /** {@code true}, wenn die Zeile physisch existiert (Leerzeilen werden gar nicht erst angelegt). */
    private static boolean rowExists(XSSFSheet sheet, int row) {
        return sheet.getRow(row) != null;
    }

    /** Alle physisch vorhandenen Zellen des Blatts (zeilenweise). */
    private static List<Cell> allCells(XSSFSheet sheet) {
        List<Cell> cells = new ArrayList<>();
        for (Row row : sheet) {
            for (Cell cell : row) {
                cells.add(cell);
            }
        }
        return cells;
    }

    private static byte[] sample(XlsxGenerator generator) {
        return generator.generate(new FileContentRequest(
                "Quartalsbericht",
                "Nur fuer den internen Gebrauch",
                "Einleitung\n\nName\tAbteilung\tBetrag\nMeier\tEinkauf\t100\r\nletzte Zeile"));
    }

    // ------------------------------------------------------------------ Grundlagen / Vertrag

    @Test
    void typeAndMetadataMatchXlsx() {
        assertThat(generator.type()).isEqualTo(GeneratedFileType.XLSX);
        assertThat(generator.type().extension()).isEqualTo("xlsx");
        assertThat(generator.type().contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    void producesZipWithPkSignature() {
        byte[] xlsx = sample(generator);
        assertThat(xlsx).isNotEmpty();
        assertThat(xlsx[0]).isEqualTo((byte) 'P');
        assertThat(xlsx[1]).isEqualTo((byte) 'K');
    }

    @Test
    void reopensAsWorkbookWithSingleFixedSheet() throws Exception {
        try (XSSFWorkbook workbook = open(sample(generator))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(workbook.getSheetName(0)).isEqualTo("Inhalt");
        }
    }

    @Test
    void sheetNameIsNeverUserControlled() throws Exception {
        byte[] xlsx = generator.generate(new FileContentRequest("Boeses/Blatt:[1]", "x", "y"));
        try (XSSFWorkbook workbook = open(xlsx)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(workbook.getSheetName(0)).isEqualTo("Inhalt");
        }
    }

    @Test
    void nullRequestIsRejected() {
        assertThatThrownBy(() -> generator.generate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Kein Inhalt.");
    }

    // ------------------------------------------------------------------ Layout / Zellpositionen

    @Test
    void writesExactCellValuesAndPositions() throws Exception {
        try (XSSFWorkbook workbook = open(sample(generator))) {
            XSSFSheet sheet = workbook.getSheet("Inhalt");

            assertThat(value(sheet, 0, 0)).isEqualTo("Quartalsbericht");
            assertThat(value(sheet, 1, 0)).isEqualTo("Nur fuer den internen Gebrauch");
            // Eine Leerzeile zwischen Kopfbereich und Inhalt.
            assertThat(rowExists(sheet, 2)).isFalse();
            assertThat(value(sheet, 3, 0)).isEqualTo("Einleitung");
            // Leere Inhaltszeile -> leere Tabellenzeile.
            assertThat(rowExists(sheet, 4)).isFalse();
            // Tabulator-getrennte Zeile -> aufeinanderfolgende Spalten.
            assertThat(value(sheet, 5, 0)).isEqualTo("Name");
            assertThat(value(sheet, 5, 1)).isEqualTo("Abteilung");
            assertThat(value(sheet, 5, 2)).isEqualTo("Betrag");
            // CRLF: genau ein abschliessendes \r wird entfernt.
            assertThat(value(sheet, 6, 0)).isEqualTo("Meier");
            assertThat(value(sheet, 6, 1)).isEqualTo("Einkauf");
            assertThat(value(sheet, 6, 2)).isEqualTo("100");
            assertThat(value(sheet, 7, 0)).isEqualTo("letzte Zeile");
            assertThat(sheet.getLastRowNum()).isEqualTo(7);
        }
    }

    @Test
    void titleIsBoldAndSubtitleIsItalic() throws Exception {
        try (XSSFWorkbook workbook = open(sample(generator))) {
            XSSFSheet sheet = workbook.getSheet("Inhalt");
            XSSFFont titleFont = ((XSSFCellStyle) sheet.getRow(0).getCell(0).getCellStyle()).getFont();
            XSSFFont subtitleFont = ((XSSFCellStyle) sheet.getRow(1).getCell(0).getCellStyle()).getFont();
            XSSFFont bodyFont = ((XSSFCellStyle) sheet.getRow(3).getCell(0).getCellStyle()).getFont();
            assertThat(titleFont.getBold()).isTrue();
            assertThat(subtitleFont.getItalic()).isTrue();
            assertThat(bodyFont.getBold()).isFalse();
            assertThat(bodyFont.getItalic()).isFalse();
        }
    }

    @Test
    void withoutSubtitleContentFollowsAfterOneBlankRow() throws Exception {
        byte[] xlsx = generator.generate(new FileContentRequest("Titel", "  ", "A\tB"));
        try (XSSFWorkbook workbook = open(xlsx)) {
            XSSFSheet sheet = workbook.getSheet("Inhalt");
            assertThat(value(sheet, 0, 0)).isEqualTo("Titel");
            assertThat(rowExists(sheet, 1)).isFalse();
            assertThat(value(sheet, 2, 0)).isEqualTo("A");
            assertThat(value(sheet, 2, 1)).isEqualTo("B");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
        }
    }

    @Test
    void withoutHeaderContentStartsInFirstRow() throws Exception {
        byte[] xlsx = generator.generate(new FileContentRequest(null, "", "Erste\nZweite"));
        try (XSSFWorkbook workbook = open(xlsx)) {
            XSSFSheet sheet = workbook.getSheet("Inhalt");
            assertThat(value(sheet, 0, 0)).isEqualTo("Erste");
            assertThat(value(sheet, 1, 0)).isEqualTo("Zweite");
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
        }
    }

    @Test
    void headerOnlyHasNoTrailingBlankRow() throws Exception {
        byte[] xlsx = generator.generate(new FileContentRequest("Titel", "Untertitel", null));
        try (XSSFWorkbook workbook = open(xlsx)) {
            XSSFSheet sheet = workbook.getSheet("Inhalt");
            assertThat(value(sheet, 0, 0)).isEqualTo("Titel");
            assertThat(value(sheet, 1, 0)).isEqualTo("Untertitel");
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
        }
    }

    @Test
    void leadingTabShiftsTextIntoSecondColumn() throws Exception {
        byte[] xlsx = generator.generate(new FileContentRequest(null, null, "\tB\t\tD\t"));
        try (XSSFWorkbook workbook = open(xlsx)) {
            XSSFSheet sheet = workbook.getSheet("Inhalt");
            // Leere Segmente erzeugen keine Zelle, verschieben aber die Spaltenposition.
            assertThat(value(sheet, 0, 0)).isNull();
            assertThat(value(sheet, 0, 1)).isEqualTo("B");
            assertThat(value(sheet, 0, 2)).isNull();
            assertThat(value(sheet, 0, 3)).isEqualTo("D");
            assertThat(value(sheet, 0, 4)).isNull();
        }
    }

    @Test
    void firstColumnHasFixedWidth() throws Exception {
        try (XSSFWorkbook workbook = open(sample(generator))) {
            assertThat(workbook.getSheet("Inhalt").getColumnWidth(0)).isEqualTo(80 * 256);
        }
    }

    // ------------------------------------------------------------------ Alles als Text (Formula-Injection)

    @Test
    void everyCellIsQuotePrefixedTextString() throws Exception {
        byte[] xlsx = generator.generate(new FileContentRequest(
                "=Titel", "+Untertitel", "a\tb\tc\n=1+1\t-1\t@SUM(A1)\n12345\t3.14\tTRUE"));
        try (XSSFWorkbook workbook = open(xlsx)) {
            List<Cell> cells = allCells(workbook.getSheet("Inhalt"));
            assertThat(cells).isNotEmpty();
            for (Cell cell : cells) {
                assertThat(cell.getCellType()).as("Zelltyp %s", cell.getAddress()).isEqualTo(CellType.STRING);
                CellStyle style = cell.getCellStyle();
                assertThat(style.getQuotePrefixed()).as("quotePrefix %s", cell.getAddress()).isTrue();
                assertThat(style.getDataFormatString()).as("Datenformat %s", cell.getAddress()).isEqualTo("@");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "=1+1",
            "+1",
            "-1",
            "@SUM(A1)",
            "=HYPERLINK(\"http://example.invalid\",\"x\")",
            "=cmd|' /C calc'!A0"
    })
    void formulaLookingInputIsStoredVerbatimAsString(String input) throws Exception {
        byte[] xlsx = generator.generate(new FileContentRequest(input, input, input));
        try (XSSFWorkbook workbook = open(xlsx)) {
            XSSFSheet sheet = workbook.getSheet("Inhalt");
            for (int row : new int[] {0, 1, 3}) {
                XSSFCell cell = sheet.getRow(row).getCell(0);
                assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
                // Woertlich - insbesondere KEIN in den Wert eingefuegtes Apostroph.
                assertThat(cell.getStringCellValue()).isEqualTo(input);
                assertThat(cell.getCellStyle().getQuotePrefixed()).isTrue();
            }
        }
        assertThat(worksheetXml(xlsx)).doesNotContain("<f>").doesNotContain("<f ");
    }

    @Test
    void tabPrefixedFormulaIsStoredVerbatimInSecondColumn() throws Exception {
        byte[] xlsx = generator.generate(new FileContentRequest(null, null, "\t=1"));
        try (XSSFWorkbook workbook = open(xlsx)) {
            XSSFCell cell = workbook.getSheet("Inhalt").getRow(0).getCell(1);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue()).isEqualTo("=1");
        }
        assertThat(worksheetXml(xlsx)).doesNotContain("<f>").doesNotContain("<f ");
    }

    // ------------------------------------------------------------------ OOXML-Escape-Muster _xHHHH_

    /**
     * SpreadsheetML (ST_Xstring) kodiert Zeichen als {@code _xHHHH_}; Excel und POI dekodieren solche Folgen
     * beim Lesen. Ohne Maskierung wuerde z. B. {@code _x0041_} als "A", {@code _x0007_} als Steuerzeichen und
     * {@code _x003D_1+1} als "=1+1" angezeigt. Erwartet: exakt woertliche Rueckgabe in Titel, Untertitel und
     * Inhalt (Spalte A und B).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "_x0041_",
            "_x000D_",
            "_x0007_",
            "_x003D_1+1",
            "_x005F_x0041_",
            "a_x0041_b",
            "_x00e4_",
            "_x00E4_",
            "_x00aB_",
            "_x0041_x0042_",
            "__x0041__",
            "_x0041_ _x000D_ _x0007_ _x005F_x0041_",
            "_x41_",
            "_x00041_",
            "_X0041_",
            "_X005F_x0041_"
    })
    void xmlEscapePatternsRoundTripVerbatim(String input) throws Exception {
        byte[] xlsx = generator.generate(new FileContentRequest(input, input, input + "\t" + input));
        try (XSSFWorkbook workbook = open(xlsx)) {
            XSSFSheet sheet = workbook.getSheet("Inhalt");
            assertThat(value(sheet, 0, 0)).as("Titel").isEqualTo(input);
            assertThat(value(sheet, 1, 0)).as("Untertitel").isEqualTo(input);
            assertThat(value(sheet, 3, 0)).as("Inhalt A").isEqualTo(input);
            assertThat(value(sheet, 3, 1)).as("Inhalt B").isEqualTo(input);
            for (Cell cell : allCells(sheet)) {
                assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            }
        }
    }

    @Test
    void xmlEscapePatternIsWrittenEscapedToSharedStrings() throws Exception {
        byte[] xlsx = generator.generate(new FileContentRequest(
                null, null, "_x0041_\n_x003D_1+1\n_X0041_\n_x41_\nkein Muster"));
        String sharedStrings = zipEntries(xlsx).get("xl/sharedStrings.xml");
        assertThat(sharedStrings).isNotBlank();
        // Der fuehrende Unterstrich wird selbst als _x005F_ kodiert -> Excel/POI lesen das Original.
        assertThat(sharedStrings).contains(">_x005F_x0041_<");
        assertThat(sharedStrings).contains(">_x005F_x003D_1+1<");
        assertThat(sharedStrings).doesNotContain(">_x0041_<").doesNotContain(">_x003D_1+1<");
        // Grosses X wird vorsorglich ebenfalls maskiert (verlustfrei, falls ein Leser es dekodiert).
        assertThat(sharedStrings).contains(">_x005F_X0041_<");
        // Kein gueltiges Muster (nur zwei Hex-Ziffern) bleibt unveraendert.
        assertThat(sharedStrings).contains(">_x41_<");
        assertThat(sharedStrings).contains(">kein Muster<");
    }

    @Test
    void coreTitlePropertyIsStoredVerbatim() throws Exception {
        String title = "_x0041_ Titel _x003D_";
        byte[] xlsx = generator.generate(new FileContentRequest(title, null, null));
        try (XSSFWorkbook workbook = open(xlsx)) {
            assertThat(workbook.getProperties().getCoreProperties().getTitle()).isEqualTo(title);
        }
        // docProps/core.xml ist kein ST_Xstring: dort wird der Titel unveraendert (ohne _x005F_) abgelegt.
        String core = zipEntries(xlsx).get("docProps/core.xml");
        assertThat(core).contains(title).doesNotContain("_x005F_");
    }

    // ------------------------------------------------------------------ Zeichen / Grenzen

    @Test
    void preservesUmlautsEszettAndEuro() throws Exception {
        byte[] xlsx = generator.generate(new FileContentRequest(
                "Grüße äöü ÄÖÜ ß", "Preis: 12 €", "Straße\tMaß €"));
        try (XSSFWorkbook workbook = open(xlsx)) {
            XSSFSheet sheet = workbook.getSheet("Inhalt");
            assertThat(value(sheet, 0, 0)).isEqualTo("Grüße äöü ÄÖÜ ß");
            assertThat(value(sheet, 1, 0)).isEqualTo("Preis: 12 €");
            assertThat(value(sheet, 3, 0)).isEqualTo("Straße");
            assertThat(value(sheet, 3, 1)).isEqualTo("Maß €");
        }
    }

    @Test
    void nullFieldsProduceValidEmptyWorkbook() throws Exception {
        byte[] xlsx = generator.generate(new FileContentRequest(null, null, null));
        assertThat(xlsx[0]).isEqualTo((byte) 'P');
        try (XSSFWorkbook workbook = open(xlsx)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(workbook.getSheetName(0)).isEqualTo("Inhalt");
            assertThat(allCells(workbook.getSheet("Inhalt"))).isEmpty();
        }
    }

    @Test
    void emptyAndBlankFieldsProduceValidWorkbook() throws Exception {
        byte[] xlsx = generator.generate(new FileContentRequest("", "   ", ""));
        try (XSSFWorkbook workbook = open(xlsx)) {
            assertThat(workbook.getSheetName(0)).isEqualTo("Inhalt");
            assertThat(allCells(workbook.getSheet("Inhalt"))).isEmpty();
        }
    }

    @Test
    void controlCharactersAreHarmless() throws Exception {
        byte[] xlsx = generator.generate(new FileContentRequest(
                "Titel\u0000X", "Sub\u0008Y", "Zeile\u0001Eins\tZwei\u001Fb"));
        try (XSSFWorkbook workbook = open(xlsx)) {
            XSSFSheet sheet = workbook.getSheet("Inhalt");
            assertThat(value(sheet, 0, 0)).isEqualTo("TitelX");
            assertThat(value(sheet, 1, 0)).isEqualTo("SubY");
            assertThat(value(sheet, 3, 0)).isEqualTo("ZeileEins");
            assertThat(value(sheet, 3, 1)).isEqualTo("Zweib");
        }
    }

    @Test
    void maximumLengthInputIsFullyPresent() throws Exception {
        String title = "T".repeat(254) + "E";
        String subtitle = "S".repeat(254) + "E";
        // Genau 10.000 Zeichen: viele Tab-getrennte Zeilen, letzte Zeile mit 'x' bis zur Grenze aufgefuellt.
        StringBuilder content = new StringBuilder();
        int lineNo = 0;
        while (content.length() < 9_900) {
            content.append("Zeile-").append(lineNo).append("\tWert-").append(lineNo).append('\n');
            lineNo++;
        }
        content.append("Ende");
        content.append("x".repeat(10_000 - content.length()));
        String body = content.toString();
        assertThat(body).hasSize(10_000);

        byte[] xlsx = generator.generate(new FileContentRequest(title, subtitle, body));
        try (XSSFWorkbook workbook = open(xlsx)) {
            XSSFSheet sheet = workbook.getSheet("Inhalt");
            assertThat(value(sheet, 0, 0)).isEqualTo(title);
            assertThat(value(sheet, 1, 0)).isEqualTo(subtitle);

            // Inhalt aus den Zellen rekonstruieren (Zeilen ab Index 3, Spalten mit \t verbunden).
            String[] expectedLines = body.split("\n", -1);
            for (int i = 0; i < expectedLines.length; i++) {
                Row row = sheet.getRow(3 + i);
                StringBuilder line = new StringBuilder();
                if (row != null) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        if (c > 0) {
                            line.append('\t');
                        }
                        Cell cell = row.getCell(c);
                        line.append(cell == null ? "" : cell.getStringCellValue());
                    }
                }
                assertThat(line.toString()).as("Zeile %d", i).isEqualTo(expectedLines[i]);
            }
        }
    }

    @Test
    void longSingleCellValueIsFullyPresent() throws Exception {
        String content = "x".repeat(9_999) + "Z";
        byte[] xlsx = generator.generate(new FileContentRequest(null, null, content));
        try (XSSFWorkbook workbook = open(xlsx)) {
            assertThat(value(workbook.getSheet("Inhalt"), 0, 0)).isEqualTo(content);
        }
    }

    // ------------------------------------------------------------------ Sicherheit auf ZIP-Ebene

    private static String worksheetXml(byte[] xlsx) throws Exception {
        StringBuilder all = new StringBuilder();
        for (Map.Entry<String, String> e : zipEntries(xlsx).entrySet()) {
            if (e.getKey().startsWith("xl/worksheets/") && e.getKey().endsWith(".xml")) {
                all.append(e.getValue()).append('\n');
            }
        }
        return all.toString();
    }

    private static byte[] hostileSample(XlsxGenerator generator) {
        return generator.generate(new FileContentRequest(
                "=HYPERLINK(\"http://example.invalid\",\"x\")",
                "=cmd|' /C calc'!A0",
                "http://example.invalid/pfad als reiner Text\n=1+1\t+1\t-1\t@SUM(A1)\n\t=1\n"
                        + "=WEBSERVICE(\"http://example.invalid\")\t[Mappe.xlsx]Blatt!A1"));
    }

    @Test
    void containsNoActiveOrEmbeddedParts() throws Exception {
        Map<String, String> entries = zipEntries(hostileSample(generator));
        assertThat(entries).isNotEmpty();
        assertThat(entries).doesNotContainKey("xl/vbaProject.bin");
        for (String name : entries.keySet()) {
            String lower = name.toLowerCase(Locale.ROOT);
            assertThat(lower).as(name)
                    .doesNotContain("vbaproject")
                    .doesNotContain("externallink")
                    .doesNotContain("connections")
                    .doesNotContain("querytable")
                    .doesNotContain("embeddings")
                    .doesNotContain("oleobject")
                    .doesNotContain("activex")
                    .doesNotContain("pivot")
                    .doesNotEndWith(".bin");
        }
    }

    @Test
    void worksheetsContainNoFormulasOrHyperlinks() throws Exception {
        String sheets = worksheetXml(hostileSample(generator));
        assertThat(sheets).isNotBlank();
        assertThat(sheets).doesNotContain("<f>").doesNotContain("<f ");
        assertThat(sheets).doesNotContain("<hyperlink");
        assertThat(sheets).doesNotContain("<oleObject").doesNotContain("<control");
    }

    @Test
    void hasNoExternalRelationships() throws Exception {
        Map<String, String> entries = zipEntries(hostileSample(generator));
        StringBuilder rels = new StringBuilder();
        entries.forEach((name, content) -> {
            if (name.endsWith(".rels")) {
                rels.append(content).append('\n');
            }
        });
        assertThat(rels.toString()).isNotBlank();
        assertThat(rels.toString()).doesNotContain("TargetMode=\"External\"");
        assertThat(rels.toString()).doesNotContain("External");
    }

    @Test
    void contentTypesAreSpreadsheetAndNotMacroEnabled() throws Exception {
        String contentTypes = zipEntries(hostileSample(generator)).get("[Content_Types].xml");
        assertThat(contentTypes).isNotBlank();
        assertThat(contentTypes).contains("spreadsheetml.sheet.main+xml");
        assertThat(contentTypes.toLowerCase(Locale.ROOT)).doesNotContain("macroenabled");
    }

    @Test
    void workbookHasNoExternalLinksHyperlinksOrFormulas() throws Exception {
        try (XSSFWorkbook workbook = open(hostileSample(generator))) {
            assertThat(workbook.getExternalLinksTable()).isEmpty();
            XSSFSheet sheet = workbook.getSheet("Inhalt");
            assertThat(sheet.getHyperlinkList()).isEmpty();
            assertThat(workbook.isMacroEnabled()).isFalse();
            for (Cell cell : allCells(sheet)) {
                assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
                assertThat(cell.getHyperlink()).isNull();
            }
        }
    }
}
