package de.internal.awareness.file;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Sicherheits- und Korrektheitstests fuer {@link PdfGenerator}: gueltiges, wieder ladbares PDF, vollstaendig
 * enthaltener Text (inkl. Umlaute/ss/Euro), Seitenumbruch/Zeilenumbruch ohne Textverlust, robuste Behandlung
 * nicht darstellbarer Zeichen und - vor allem - KEINE aktiven Inhalte (kein JavaScript, keine Aktionen, keine
 * Annotationen/Links, keine Formulare, keine eingebetteten Dateien, keine Verschluesselung).
 *
 * <p>Die Sicherheitspruefung arbeitet auf dem wieder geladenen Dokument: Neben gezielten Pruefungen von Katalog
 * und Seiten durchsucht ein generischer Scan ALLE COS-Objekte (vom Trailer aus erreichbar sowie jedes Objekt der
 * Xref-Tabelle) nach verbotenen Dictionary-Schluesseln. Das gilt ausdruecklich auch dann, wenn der
 * Benutzertext selbst Zeichenketten wie {@code /JavaScript} oder {@code /OpenAction} enthaelt - diese duerfen
 * nur als Text im (komprimierten) Content-Stream vorkommen.</p>
 */
class PdfGeneratorTest {

    /** Seitenrand des Layouts in Punkt (muss zu {@link PdfGenerator} passen). */
    private static final float MARGIN = 50f;

    /** Kleine Toleranz fuer Rundungen bei Positionsvergleichen (Punkt). */
    private static final float TOLERANCE = 1.0f;

    /**
     * Dictionary-Schluessel, die in einem passiven Trainings-PDF NIRGENDS vorkommen duerfen (aktive Inhalte,
     * Aktionen, Formulare, eingebettete Dateien, Annotationen, Verschluesselung). {@code S} ist der Aktionstyp-
     * Schluessel von Aktions-Dictionaries.
     */
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "JS", "JavaScript", "Launch", "EmbeddedFile", "EmbeddedFiles", "EF", "OpenAction", "AA", "URI",
            "SubmitForm", "ImportData", "GoToR", "GoToE", "RichMedia", "XFA", "S", "Annots", "AcroForm", "Names",
            "Encrypt");

    /** Zeichenketten, die wie PDF-Aktionen aussehen und als reiner Benutzertext eingegeben werden. */
    private static final String MALICIOUS_LOOKING_TEXT = String.join("\n",
            "/JavaScript (app.alert(1))",
            "/OpenAction << /S /JavaScript /JS (app.alert(2)) >>",
            "/Launch /F (cmd.exe) /AA /URI (http://example.invalid/t/abc)",
            "/EmbeddedFiles /SubmitForm /ImportData /GoToR /RichMedia /XFA",
            "endstream endobj 99 0 obj << /Type /Action /S /Launch >> endobj");

    private final PdfGenerator generator = new PdfGenerator();

    // ------------------------------------------------------------------------------------------------------
    // Hilfsfunktionen
    // ------------------------------------------------------------------------------------------------------

    private byte[] generate(String title, String subtitle, String content) {
        return generator.generate(new FileContentRequest(title, subtitle, content));
    }

    private static String extractText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** Entfernt saemtlichen Leerraum, damit Textvollstaendigkeit unabhaengig von Zeilen-/Seitenumbruechen pruefbar ist. */
    private static String withoutWhitespace(String text) {
        return text.replaceAll("\\s+", "");
    }

    /** Sammelt alle Zeichenpositionen (fuer Layout-Pruefungen: Raender, Zeilenabstaende). */
    private static final class PositionCollector extends PDFTextStripper {

        private final List<TextPosition> positions = new ArrayList<>();
        private final List<String> chunks = new ArrayList<>();
        private final List<Float> chunkBaselines = new ArrayList<>();

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            positions.addAll(textPositions);
            if (!textPositions.isEmpty()) {
                chunks.add(text);
                chunkBaselines.add(textPositions.get(0).getYDirAdj());
            }
            super.writeString(text, textPositions);
        }

        float baselineOf(String chunkText) {
            int index = chunks.indexOf(chunkText);
            assertThat(index).as("Textstueck '%s' gefunden", chunkText).isNotNegative();
            return chunkBaselines.get(index);
        }
    }

    private static PositionCollector collectPositions(PDDocument document) throws IOException {
        PositionCollector collector = new PositionCollector();
        collector.getText(document);
        return collector;
    }

    /**
     * Generischer Sicherheits-Scan ueber ALLE COS-Objekte: startet beim Trailer und bei jedem Objekt der
     * Xref-Tabelle, folgt allen Referenzen (Dictionaries, Arrays, Streams) und meldet jeden verbotenen
     * Schluessel sowie jedes Dictionary vom Typ {@code /Action}.
     */
    private static List<String> findForbiddenEntries(PDDocument document) {
        COSDocument cosDocument = document.getDocument();
        Deque<COSBase> todo = new ArrayDeque<>();
        todo.push(cosDocument.getTrailer());
        for (COSObjectKey key : cosDocument.getXrefTable().keySet()) {
            todo.push(cosDocument.getObjectFromPool(key));
        }
        Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> findings = new ArrayList<>();
        while (!todo.isEmpty()) {
            COSBase current = todo.pop();
            if (current instanceof COSObject reference) {
                current = reference.getObject();
            }
            if (current == null || !visited.add(current)) {
                continue;
            }
            if (current instanceof COSDictionary dictionary) {
                for (COSName key : dictionary.keySet()) {
                    if (FORBIDDEN_KEYS.contains(key.getName())) {
                        findings.add("/" + key.getName());
                    }
                }
                if (COSName.getPDFName("Action").equals(dictionary.getDictionaryObject(COSName.TYPE))) {
                    findings.add("/Type /Action");
                }
                for (COSBase value : dictionary.getValues()) {
                    todo.push(value);
                }
            } else if (current instanceof COSArray array) {
                for (COSBase item : array) {
                    todo.push(item);
                }
            }
        }
        return findings;
    }

    // ------------------------------------------------------------------------------------------------------
    // Typ / Grundformat
    // ------------------------------------------------------------------------------------------------------

    @Test
    void typeIsPdfWithExtensionAndContentType() {
        assertThat(generator.type()).isEqualTo(GeneratedFileType.PDF);
        assertThat(GeneratedFileType.PDF.extension()).isEqualTo("pdf");
        assertThat(GeneratedFileType.PDF.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void startsWithPdfHeader() {
        byte[] pdf = generate("Titel", "Untertitel", "Inhalt");
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void isReloadableAndUsesA4Pages() throws Exception {
        byte[] pdf = generate("Rechnung September", "Untertitel", "Zeile A\nZeile B");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            PDRectangle mediaBox = document.getPage(0).getMediaBox();
            assertThat(mediaBox.getWidth()).isCloseTo(PDRectangle.A4.getWidth(), within(0.01f));
            assertThat(mediaBox.getHeight()).isCloseTo(PDRectangle.A4.getHeight(), within(0.01f));
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Textinhalt
    // ------------------------------------------------------------------------------------------------------

    @Test
    void containsTitleSubtitleAndBodyLinesIncludingGermanCharacters() throws Exception {
        byte[] pdf = generate("Mitteilung der Personalabteilung", "Untertitel für März",
                "Erste Zeile\nViele Grüße aus Köln\nStraße und Maß: ß\nGebühr: 5 € – „zitiert“\nÄÖÜ äöü");
        String text = extractText(pdf);
        assertThat(text)
                .contains("Mitteilung der Personalabteilung")
                .contains("Untertitel für März")
                .contains("Erste Zeile")
                .contains("Viele Grüße aus Köln")
                .contains("Straße und Maß: ß")
                .contains("Gebühr: 5 € – „zitiert“")
                .contains("ÄÖÜ äöü")
                .contains("Grüße").contains("ß").contains("€");
        assertThat(text).doesNotContain("?");
    }

    @Test
    void decomposedUmlautsAreComposedInsteadOfReplaced() throws Exception {
        // "u" + kombinierendes Trema (z. B. aus macOS-Zwischenablage) muss als "ü" erscheinen, nicht als "u?".
        String text = extractText(generate(null, null, "Grüße"));
        assertThat(text).contains("Grüße").doesNotContain("?");
    }

    @Test
    void longContentProducesSeveralPagesAndKeepsEveryLine() throws Exception {
        StringBuilder content = new StringBuilder();
        List<String> markers = new ArrayList<>();
        int lineNumber = 0;
        while (true) {
            String marker = String.format("Z%04d-Marke", lineNumber);
            // Unterschiedlich lange Zeilen: manche passen in eine Zeile, manche muessen umbrechen.
            String line = marker + " " + "Wort ".repeat(lineNumber % 37);
            if (content.length() + line.length() + 1 > 10_000) {
                break;
            }
            if (lineNumber > 0) {
                content.append('\n');
            }
            content.append(line);
            markers.add(marker);
            lineNumber++;
        }
        assertThat(content.length()).isGreaterThan(9_800).isLessThanOrEqualTo(10_000);

        byte[] pdf = generate("Langer Titel", "Langer Untertitel", content.toString());
        assertThat(pdf.length).isLessThan(1024 * 1024);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains(markers);
            // Kein stiller Textverlust: ohne Leerraum muss der gesamte Eingabetext exakt erhalten sein.
            assertThat(withoutWhitespace(text))
                    .isEqualTo(withoutWhitespace("Langer Titel" + "Langer Untertitel" + content));
        }
    }

    @Test
    void longWordIsHardBrokenWithoutLosingCharacters() throws Exception {
        String longWord = "Unterbrechungsfrei".repeat(555).substring(0, 9_990) + "ENDE012345";
        assertThat(longWord).hasSize(10_000);

        byte[] pdf = generate(null, null, longWord);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(withoutWhitespace(text)).isEqualTo(longWord);
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
        }
    }

    @Test
    void allTextStaysWithinPageMargins() throws Exception {
        String longWord = "W".repeat(600);
        String longLine = "Ein sehr langer Satz mit vielen Woertern, der umbrechen muss. ".repeat(40);
        String longTitle = "Titelwort ".repeat(25) + "X".repeat(5);
        byte[] pdf = generate(longTitle, "Untertitel ".repeat(23), longWord + "\n" + longLine + "\n\tEingerueckt");

        try (PDDocument document = Loader.loadPDF(pdf)) {
            float pageWidth = PDRectangle.A4.getWidth();
            float pageHeight = PDRectangle.A4.getHeight();
            PositionCollector collector = collectPositions(document);
            assertThat(collector.positions).isNotEmpty();
            for (TextPosition position : collector.positions) {
                assertThat(position.getXDirAdj()).isGreaterThanOrEqualTo(MARGIN - TOLERANCE);
                assertThat(position.getXDirAdj() + position.getWidthDirAdj())
                        .as("Zeichen '%s' rechts innerhalb des Rands", position.getUnicode())
                        .isLessThanOrEqualTo(pageWidth - MARGIN + TOLERANCE);
                // getYDirAdj = Grundlinie, gemessen von der Oberkante der Seite.
                assertThat(position.getYDirAdj()).isGreaterThanOrEqualTo(MARGIN - TOLERANCE);
                assertThat(position.getYDirAdj()).isLessThanOrEqualTo(pageHeight - MARGIN + TOLERANCE);
            }
        }
    }

    @Test
    void emptyLinesArePreservedAsVerticalSpace() throws Exception {
        float singleGap;
        try (PDDocument document = Loader.loadPDF(generate(null, null, "Oben\nUnten"))) {
            PositionCollector collector = collectPositions(document);
            singleGap = collector.baselineOf("Unten") - collector.baselineOf("Oben");
        }
        float tripleGap;
        try (PDDocument document = Loader.loadPDF(generate(null, null, "Oben\n\n\nUnten"))) {
            PositionCollector collector = collectPositions(document);
            tripleGap = collector.baselineOf("Unten") - collector.baselineOf("Oben");
        }
        assertThat(singleGap).isPositive();
        // Zwei Leerzeilen dazwischen => etwa dreifacher Abstand.
        assertThat(tripleGap).isCloseTo(3 * singleGap, within(TOLERANCE));
    }

    @Test
    void tabsAndCarriageReturnsAreHandledWithoutReplacementCharacters() throws Exception {
        String text = extractText(generate("Titel", null, "Spalte\tWert\r\nZweite\rZeile\r\n\tEingerueckt"));
        assertThat(text).contains("Spalte").contains("Wert").contains("Zweite").contains("Zeile")
                .contains("Eingerueckt");
        // Tab und CR sind in WinAnsi nicht darstellbar - sie duerfen aber NICHT als '?' erscheinen.
        assertThat(text).doesNotContain("?");
    }

    @Test
    void longTitleAndSubtitleAreWrappedCompletely() throws Exception {
        String title = ("Titelwort" + "ABCDEFGHIJ".repeat(3) + " ").repeat(6) + "T".repeat(15);
        String subtitle = ("Untertitel" + "KLMNOPQRST".repeat(3) + " ").repeat(6) + "U".repeat(9);
        assertThat(title).hasSize(255);
        assertThat(subtitle).hasSize(255);
        String text = extractText(generate(title, subtitle, "Inhalt"));
        assertThat(withoutWhitespace(text)).isEqualTo(withoutWhitespace(title + subtitle + "Inhalt"));
    }

    // ------------------------------------------------------------------------------------------------------
    // Robustheit / Eingabevalidierung
    // ------------------------------------------------------------------------------------------------------

    @Test
    void nullRequestIsRejected() {
        assertThatThrownBy(() -> generator.generate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Kein Inhalt.");
    }

    @Test
    void nullFieldsProduceValidPdf() throws Exception {
        byte[] pdf = generate(null, null, null);
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(new PDFTextStripper().getText(document)).isBlank();
        }
    }

    @Test
    void emptyAndBlankFieldsProduceValidPdf() throws Exception {
        for (FileContentRequest request : List.of(
                new FileContentRequest("", "", ""),
                new FileContentRequest("   ", "\t", " \n \n "),
                new FileContentRequest("\u0000", "\u0007", "\u0001\u0002"))) {
            byte[] pdf = generator.generate(request);
            try (PDDocument document = Loader.loadPDF(pdf)) {
                assertThat(document.getNumberOfPages()).isEqualTo(1);
                assertThat(new PDFTextStripper().getText(document)).isBlank();
                // Kein Titel => kein Title-Eintrag in den Dokumentinformationen.
                COSDictionary info = document.getDocument().getTrailer().getCOSDictionary(COSName.INFO);
                assertThat(info == null || !info.containsKey(COSName.TITLE)).isTrue();
            }
        }
    }

    @Test
    void unencodableCharactersAreReplacedWithoutException() throws Exception {
        String emoji = "😀";
        FileContentRequest request = new FileContentRequest(
                "Titel " + emoji, "Sub 中", "Emoji " + emoji + " und CJK 中文 Ende Weiter");
        byte[] pdf = generator.generate(request);
        String text = extractText(pdf);
        assertThat(text)
                .contains("Titel ?")
                .contains("Sub ?")
                .contains("Emoji ? und CJK ?? Ende")
                .contains("Weiter");
    }

    @Test
    void controlCharactersAreHarmless() throws Exception {
        String text = extractText(generate("Titel\u0000X", "Sub\u0007Y", "Zeile\u0000Eins\nZeile\u0007Zwei\u0008"));
        assertThat(text).contains("TitelX").contains("SubY").contains("ZeileEins").contains("ZeileZwei");
        assertThat(text).doesNotContain("?");
    }

    @Test
    void generatorIsStatelessAndThreadSafe() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                String marker = "Parallel" + i;
                futures.add(executor.submit(() -> generate(marker, "Untertitel", marker + "Inhalt\nZeile 2")));
            }
            for (int i = 0; i < futures.size(); i++) {
                String text = extractText(futures.get(i).get());
                assertThat(text).contains("Parallel" + i + "Inhalt").contains("Zeile 2");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void worstCaseInputsStayFarBelowFiveMebibytes() {
        String title = "T".repeat(255);
        String subtitle = "S".repeat(255);
        for (String content : List.of(
                "\n".repeat(10_000),
                "a\n".repeat(5_000),
                "W".repeat(10_000),
                "😀".repeat(5_000),
                "x ".repeat(5_000))) {
            byte[] pdf = generate(title, subtitle, content);
            assertThat(pdf.length).isLessThan(1024 * 1024);
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Dokumentinformationen / Schriften
    // ------------------------------------------------------------------------------------------------------

    @Test
    void documentInformationContainsOnlyTheTitle() throws Exception {
        byte[] pdf = generate("Sicherheitshinweis", "Untertitel", "Inhalt");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            COSDictionary info = document.getDocument().getTrailer().getCOSDictionary(COSName.INFO);
            assertThat(info).isNotNull();
            assertThat(info.keySet()).containsExactly(COSName.TITLE);
            assertThat(info.getString(COSName.TITLE)).isEqualTo("Sicherheitshinweis");
            // Keine XMP-Metadaten.
            assertThat(document.getDocumentCatalog().getCOSObject().containsKey(COSName.METADATA)).isFalse();
        }
    }

    @Test
    void usesOnlyNonEmbeddedStandard14FontsAndNoImages() throws Exception {
        byte[] pdf = generate("Titel", "Untertitel", "Inhalt");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            for (PDPage page : document.getPages()) {
                PDResources resources = page.getResources();
                assertThat(resources.getXObjectNames()).isEmpty();
                List<String> fontNames = new ArrayList<>();
                for (COSName name : resources.getFontNames()) {
                    PDFont font = resources.getFont(name);
                    assertThat(font).isInstanceOf(PDType1Font.class);
                    assertThat(font.isStandard14()).isTrue();
                    assertThat(font.isEmbedded()).isFalse();
                    fontNames.add(font.getName());
                }
                assertThat(fontNames).containsExactlyInAnyOrder("Helvetica-Bold", "Helvetica-Oblique", "Helvetica");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Sicherheit: keine aktiven Inhalte
    // ------------------------------------------------------------------------------------------------------

    @Test
    void isNotEncrypted() throws Exception {
        try (PDDocument document = Loader.loadPDF(generate("Titel", "Untertitel", "Inhalt"))) {
            assertThat(document.isEncrypted()).isFalse();
            assertThat(document.getDocument().getTrailer().containsKey(COSName.ENCRYPT)).isFalse();
        }
    }

    @Test
    void catalogAndPagesContainNoActiveContent() throws Exception {
        byte[] pdf = generate("Titel " + MALICIOUS_LOOKING_TEXT, MALICIOUS_LOOKING_TEXT, MALICIOUS_LOOKING_TEXT);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDDocumentCatalog catalog = document.getDocumentCatalog();
            assertThat(catalog.getOpenAction()).isNull();
            COSDictionary catalogDictionary = catalog.getCOSObject();
            assertThat(catalogDictionary.containsKey(COSName.AA)).isFalse();
            assertThat(catalogDictionary.containsKey(COSName.NAMES)).isFalse();
            assertThat(catalogDictionary.containsKey(COSName.ACRO_FORM)).isFalse();
            assertThat(catalogDictionary.containsKey(COSName.JAVA_SCRIPT)).isFalse();
            assertThat(catalogDictionary.containsKey(COSName.OPEN_ACTION)).isFalse();
            assertThat(catalog.getAcroForm()).isNull();
            for (PDPage page : document.getPages()) {
                assertThat(page.getAnnotations()).isEmpty();
                assertThat(page.getCOSObject().containsKey(COSName.ANNOTS)).isFalse();
                assertThat(page.getCOSObject().containsKey(COSName.AA)).isFalse();
            }
        }
    }

    @Test
    void noActiveContentAnywhereInObjectGraphEvenWithMaliciousLookingText() throws Exception {
        byte[] pdf = generate("/OpenAction /JavaScript", "/Launch /URI", MALICIOUS_LOOKING_TEXT);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(findForbiddenEntries(document)).isEmpty();

            // Die verdaechtigen Zeichenketten sind ausschliesslich sichtbarer Text.
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("/JavaScript (app.alert(1))")
                    .contains("/OpenAction << /S /JavaScript /JS (app.alert(2)) >>")
                    .contains("/Launch /F (cmd.exe) /AA /URI (http://example.invalid/t/abc)")
                    .contains("endstream endobj 99 0 obj << /Type /Action /S /Launch >> endobj");
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("/OpenAction /JavaScript");
        }
    }

    @Test
    void rawFileContainsNoActiveContentKeywordsEvenWithMaliciousLookingText() {
        // Ergaenzend zur Objektgraph-Pruefung (der eigentlichen Garantie): Harmloser Benutzertext soll bei einfachen
        // Schluesselwort-Pruefungen der Rohbytes keine Fehlalarme ausloesen - er steht nur als Text im komprimierten
        // Content-Stream bzw. als Hex-String im Titel. Es gibt keine aktiven Strukturen, die verborgen werden koennten.
        byte[] pdf = generate("/OpenAction /JavaScript /JS /AA", "/Launch /URI /EmbeddedFile", MALICIOUS_LOOKING_TEXT);
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(raw).doesNotContain(
                "/JavaScript", "/JS", "/OpenAction", "/AA", "/Launch", "/URI", "/EmbeddedFile", "/SubmitForm",
                "/ImportData", "/GoToR", "/RichMedia", "/XFA", "/AcroForm", "/Annots", "/Encrypt", "/ObjStm");
    }

    @Test
    void generationDoesNotThrowForAnyBasicMultilingualPlaneCharacter() {
        // Jedes BMP-Zeichen (ausser Surrogates) einmal: nie eine Exception, auch fuer nicht kodierbare Zeichen.
        StringBuilder all = new StringBuilder();
        for (int c = 0; c <= 0xFFFF; c++) {
            if (!Character.isSurrogate((char) c)) {
                all.append((char) c);
            }
            if (all.length() >= 9_000) {
                String chunk = all.toString();
                assertThatCode(() -> generate(chunk.substring(0, 255), chunk.substring(255, 510), chunk))
                        .doesNotThrowAnyException();
                all.setLength(0);
            }
        }
        String rest = all.toString();
        assertThatCode(() -> generate(rest, rest, rest)).doesNotThrowAnyException();
    }
}
