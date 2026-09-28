package de.internal.awareness.file;

import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Korrektheits- und Sicherheitstests fuer {@link PptxGenerator}.
 *
 * <p>Geprueft wird zum einen der Inhalt (Titelfolie, Inhaltsfolien, Paginierung ohne Textverlust, Umlaute,
 * Steuerzeichen, leere Eingaben), zum anderen auf ZIP-Ebene, dass die Praesentation PASSIV bleibt: keine
 * Makros (vbaProject.bin), keine eingebetteten Objekte/OLE/ActiveX, keine Medien/Bilder, keine Hyperlinks,
 * keine externen Relationships und kein makrofaehiger Content-Type.</p>
 */
class PptxGeneratorTest {

    private final PptxGenerator generator = new PptxGenerator();

    // ------------------------------------------------------------------------------------------------
    // Hilfsfunktionen
    // ------------------------------------------------------------------------------------------------

    private static XMLSlideShow open(byte[] pptx) throws Exception {
        return new XMLSlideShow(new ByteArrayInputStream(pptx));
    }

    /** Alle ZIP-Eintraege (Name -> Inhalt als UTF-8-Text) in Dateireihenfolge. */
    private static Map<String, String> zipEntries(byte[] pptx) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(pptx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /** Alle Text-Shapes einer Folie in Dokumentreihenfolge (Titel zuerst, dann Untertitel/Inhalt). */
    private static List<XSLFTextShape> textShapes(XSLFSlide slide) {
        List<XSLFTextShape> shapes = new ArrayList<>();
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape textShape) {
                shapes.add(textShape);
            }
        }
        return shapes;
    }

    private static List<String> paragraphs(XSLFTextShape shape) {
        List<String> texts = new ArrayList<>();
        for (XSLFTextParagraph paragraph : shape.getTextParagraphs()) {
            texts.add(paragraph.getText());
        }
        return texts;
    }

    /** Folientitel einer Inhaltsfolie (erstes Text-Shape). */
    private static String slideTitle(XSLFSlide slide) {
        return textShapes(slide).get(0).getText();
    }

    /** Absaetze des Inhaltsbereichs einer Inhaltsfolie (zweites Text-Shape). */
    private static List<String> bodyParagraphs(XSLFSlide slide) {
        return paragraphs(textShapes(slide).get(1));
    }

    /** Alle Inhaltsabsaetze ueber alle Inhaltsfolien (ab Folie 2) hinweg, in Reihenfolge. */
    private static List<String> allBodyParagraphs(XMLSlideShow show) {
        List<String> all = new ArrayList<>();
        List<XSLFSlide> slides = show.getSlides();
        for (int i = 1; i < slides.size(); i++) {
            all.addAll(bodyParagraphs(slides.get(i)));
        }
        return all;
    }

    private static List<String> allSlideText(XMLSlideShow show) {
        List<String> texts = new ArrayList<>();
        for (XSLFSlide slide : show.getSlides()) {
            for (XSLFTextShape shape : textShapes(slide)) {
                texts.add(shape.getText());
            }
        }
        return texts;
    }

    // ------------------------------------------------------------------------------------------------
    // Vertrag / Metadaten
    // ------------------------------------------------------------------------------------------------

    @Test
    void reportsPptxTypeWithExtensionAndContentType() {
        assertThat(generator.type()).isEqualTo(GeneratedFileType.PPTX);
        assertThat(generator.type().extension()).isEqualTo("pptx");
        assertThat(generator.type().contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

    @Test
    void rejectsNullRequest() {
        assertThatThrownBy(() -> generator.generate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Kein Inhalt.");
    }

    @Test
    void producesZipSignature() {
        byte[] pptx = generator.generate(new FileContentRequest("Titel", null, "Inhalt"));
        assertThat(pptx).isNotEmpty();
        assertThat(pptx[0]).isEqualTo((byte) 'P');
        assertThat(pptx[1]).isEqualTo((byte) 'K');
    }

    // ------------------------------------------------------------------------------------------------
    // Inhalt
    // ------------------------------------------------------------------------------------------------

    @Test
    void reopensWithTitleSlideAndContentSlideInOrder() throws Exception {
        byte[] pptx = generator.generate(
                new FileContentRequest("Quartalsbericht", "Vertraulich - intern", "Zeile A\nZeile B\nZeile C"));

        try (XMLSlideShow show = open(pptx)) {
            List<XSLFSlide> slides = show.getSlides();
            assertThat(slides).hasSize(2);

            // Folie 1: nur Titel + Untertitel (keine leeren "Klicken Sie..."-Platzhalter).
            List<XSLFTextShape> titleShapes = textShapes(slides.get(0));
            assertThat(titleShapes).extracting(XSLFTextShape::getText)
                    .containsExactly("Quartalsbericht", "Vertraulich - intern");
            assertThat(titleShapes.get(0).getTextType()).isIn(Placeholder.CENTERED_TITLE, Placeholder.TITLE);
            assertThat(titleShapes.get(1).getTextType()).isEqualTo(Placeholder.SUBTITLE);

            // Folie 2: Dokumenttitel als Folientitel, Inhalt zeilenweise als Absaetze.
            assertThat(slideTitle(slides.get(1))).isEqualTo("Quartalsbericht");
            assertThat(bodyParagraphs(slides.get(1))).containsExactly("Zeile A", "Zeile B", "Zeile C");
            assertThat(textShapes(slides.get(1))).hasSize(2);
        }
    }

    @Test
    void usesTitleSlideAndTitleAndContentLayouts() throws Exception {
        byte[] pptx = generator.generate(new FileContentRequest("Titel", "Untertitel", "Inhalt"));
        try (XMLSlideShow show = open(pptx)) {
            assertThat(show.getSlides().get(0).getSlideLayout().getType()).isEqualTo(SlideLayout.TITLE);
            assertThat(show.getSlides().get(1).getSlideLayout().getType()).isEqualTo(SlideLayout.TITLE_AND_CONTENT);
        }
    }

    @Test
    void removesUnusedPlaceholdersSoNoTemplatePromptTextRemains() throws Exception {
        // Nur Titel, kein Untertitel, kein Inhalt -> genau ein Shape auf genau einer Folie.
        byte[] pptx = generator.generate(new FileContentRequest("Nur Titel", null, null));
        try (XMLSlideShow show = open(pptx)) {
            assertThat(show.getSlides()).hasSize(1);
            assertThat(textShapes(show.getSlides().get(0))).extracting(XSLFTextShape::getText)
                    .containsExactly("Nur Titel");
        }
        // Auch mit Inhalt: kein Vorlagen-Aufforderungstext ("Click to edit ...") in irgendeiner Folie.
        byte[] withContent = generator.generate(new FileContentRequest("T", null, "Inhalt"));
        zipEntries(withContent).forEach((name, xml) -> {
            if (name.startsWith("ppt/slides/")) {
                assertThat(xml).doesNotContain("Click to edit").doesNotContain("Second level");
            }
        });
    }

    @Test
    void contentWithoutTitleUsesDefaultHeading() throws Exception {
        byte[] pptx = generator.generate(new FileContentRequest(null, null, "Nur Inhalt"));
        try (XMLSlideShow show = open(pptx)) {
            assertThat(show.getSlides()).hasSize(2);
            // Titelfolie ohne Titel/Untertitel bleibt leer (keine leeren Platzhalter).
            assertThat(textShapes(show.getSlides().get(0))).isEmpty();
            assertThat(slideTitle(show.getSlides().get(1))).isEqualTo("Inhalt");
            assertThat(bodyParagraphs(show.getSlides().get(1))).containsExactly("Nur Inhalt");
        }
    }

    @Test
    void preservesEmptyLinesAndStripsTrailingCarriageReturn() throws Exception {
        byte[] pptx = generator.generate(new FileContentRequest("T", null, "Eins\r\n\r\nDrei\r\n  eingerueckt"));
        try (XMLSlideShow show = open(pptx)) {
            assertThat(bodyParagraphs(show.getSlides().get(1)))
                    .containsExactly("Eins", "", "Drei", "  eingerueckt");
        }
    }

    @Test
    void longContentIsPaginatedAcrossSlidesWithoutLoss() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 125; i++) {
            if (i % 10 == 9) {
                lines.add("");
            } else {
                String prefix = String.format(Locale.ROOT, "Zeile %03d: ", i);
                lines.add(prefix + "x".repeat(79 - prefix.length()));
            }
        }
        String content = String.join("\n", lines);
        assertThat(content.length()).isBetween(9_000, 10_000);

        byte[] pptx = generator.generate(new FileContentRequest("Langes Dokument", "Sub", content));

        try (XMLSlideShow show = open(pptx)) {
            List<XSLFSlide> slides = show.getSlides();
            assertThat(slides.size()).isGreaterThanOrEqualTo(1 + 3);

            // Jede Zeile genau einmal und in Reihenfolge - kein stiller Textverlust.
            assertThat(allBodyParagraphs(show)).containsExactlyElementsOf(lines);

            for (int i = 1; i < slides.size(); i++) {
                List<String> body = bodyParagraphs(slides.get(i));
                assertThat(body).isNotEmpty();
                assertThat(body.size()).isLessThanOrEqualTo(PptxGenerator.MAX_LINES_PER_SLIDE);
                assertThat(body.stream().mapToInt(String::length).sum())
                        .isLessThanOrEqualTo(PptxGenerator.MAX_CHARS_PER_SLIDE);
                String expectedTitle = i == 1 ? "Langes Dokument" : "Langes Dokument (Fortsetzung)";
                assertThat(slideTitle(slides.get(i))).isEqualTo(expectedTitle);
            }
        }
    }

    @Test
    void characterBudgetSplitsSlidesWithLongLines() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.add(String.format(Locale.ROOT, "Absatz %02d ", i) + "wort ".repeat(97));
        }
        String content = String.join("\n", lines);
        assertThat(content.length()).isLessThanOrEqualTo(10_000);

        byte[] pptx = generator.generate(new FileContentRequest("Absaetze", null, content));

        try (XMLSlideShow show = open(pptx)) {
            assertThat(allBodyParagraphs(show)).containsExactlyElementsOf(lines);
            for (int i = 1; i < show.getSlides().size(); i++) {
                assertThat(bodyParagraphs(show.getSlides().get(i)).stream().mapToInt(String::length).sum())
                        .isLessThanOrEqualTo(PptxGenerator.MAX_CHARS_PER_SLIDE);
            }
            // 20 Absaetze a ~500 Zeichen passen nicht auf eine Folie.
            assertThat(show.getSlides().size()).isGreaterThan(1 + 3);
        }
    }

    @Test
    void overlongSingleLineIsSplitWithoutLoss() throws Exception {
        String noSpaces = "y".repeat(4_000);
        String words = "Wort ".repeat(700).trim();
        String content = "Anfang\n" + noSpaces + "\n" + words + "\nEnde";

        byte[] pptx = generator.generate(new FileContentRequest("Lang", null, content));

        try (XMLSlideShow show = open(pptx)) {
            List<String> body = allBodyParagraphs(show);
            // Nichts geht verloren: Die Teilstuecke ergeben aneinandergereiht exakt den Originaltext.
            assertThat(String.join("", body)).isEqualTo(content.replace("\n", ""));
            assertThat(body.get(0)).isEqualTo("Anfang");
            assertThat(body.get(body.size() - 1)).isEqualTo("Ende");
            assertThat(body).allSatisfy(p ->
                    assertThat(p.length()).isLessThanOrEqualTo(PptxGenerator.MAX_CHARS_PER_SLIDE));
            // Die ueberlange Zeile ohne Leerzeichen wird hart geteilt.
            assertThat(body).contains("y".repeat(PptxGenerator.MAX_CHARS_PER_SLIDE));
            for (int i = 2; i < show.getSlides().size(); i++) {
                assertThat(slideTitle(show.getSlides().get(i))).isEqualTo("Lang (Fortsetzung)");
            }
        }
    }

    @Test
    void overlongLineIsNeverSplitInsideSurrogatePair() throws Exception {
        // Das Emoji (2 UTF-16-Zeichen) liegt genau auf der Teilungsgrenze und darf nicht zerrissen werden.
        String emoji = "\uD83D\uDE00";
        String line = "a".repeat(PptxGenerator.MAX_CHARS_PER_SLIDE - 1) + emoji + "b".repeat(100);

        byte[] pptx = generator.generate(new FileContentRequest("T", null, line));

        try (XMLSlideShow show = open(pptx)) {
            List<String> body = allBodyParagraphs(show);
            assertThat(body).hasSize(2);
            assertThat(String.join("", body)).isEqualTo(line);
            assertThat(body.get(1)).startsWith(emoji);
        }
    }

    @Test
    void preservesUmlautsSharpSAndEuro() throws Exception {
        String title = "Gr\u00fc\u00dfe aus M\u00fcnchen";
        String subtitle = "\u00c4rger \u00fcber \u00d6l - 100 \u20ac";
        String content = "Stra\u00dfe \u00e4\u00f6\u00fc \u00c4\u00d6\u00dc \u00df \u20ac";

        byte[] pptx = generator.generate(new FileContentRequest(title, subtitle, content));

        try (XMLSlideShow show = open(pptx)) {
            assertThat(allSlideText(show)).contains(title, subtitle);
            assertThat(allBodyParagraphs(show)).containsExactly(content);
            assertThat(show.getProperties().getCoreProperties().getTitle()).isEqualTo(title);
        }
    }

    @Test
    void nullFieldsProduceValidSingleSlidePresentation() throws Exception {
        byte[] pptx = generator.generate(new FileContentRequest(null, null, null));
        try (XMLSlideShow show = open(pptx)) {
            assertThat(show.getSlides()).hasSize(1);
            assertThat(textShapes(show.getSlides().get(0))).isEmpty();
        }
    }

    @Test
    void emptyAndBlankFieldsAreTreatedAsAbsent() throws Exception {
        for (FileContentRequest request : List.of(
                new FileContentRequest("", "", ""),
                new FileContentRequest("   ", "\t", " \r\n \n "))) {
            byte[] pptx = generator.generate(request);
            try (XMLSlideShow show = open(pptx)) {
                assertThat(show.getSlides()).hasSize(1);
                assertThat(textShapes(show.getSlides().get(0))).isEmpty();
            }
        }
    }

    @Test
    void controlCharactersAreHarmless() throws Exception {
        byte[] pptx = generator.generate(
                new FileContentRequest("Titel\u0000X", "Sub\u0008Y", "Zeile\u0001Eins\n\u001fZwei\u000b"));
        try (XMLSlideShow show = open(pptx)) {
            assertThat(allSlideText(show)).contains("TitelX", "SubY");
            assertThat(allBodyParagraphs(show)).containsExactly("ZeileEins", "Zwei");
        }
    }

    @Test
    void multiLineTitleIsKeptCompletely() throws Exception {
        byte[] pptx = generator.generate(new FileContentRequest("Erste Zeile\r\nZweite Zeile", null, "x"));
        try (XMLSlideShow show = open(pptx)) {
            assertThat(paragraphs(textShapes(show.getSlides().get(0)).get(0)))
                    .containsExactly("Erste Zeile", "Zweite Zeile");
        }
    }

    @Test
    void maximumLengthTitleAndSubtitleArePreserved() throws Exception {
        String title = "T".repeat(255);
        String subtitle = "S".repeat(255);
        byte[] pptx = generator.generate(new FileContentRequest(title, subtitle, "Inhalt"));
        try (XMLSlideShow show = open(pptx)) {
            assertThat(textShapes(show.getSlides().get(0))).extracting(XSLFTextShape::getText)
                    .containsExactly(title, subtitle);
            assertThat(slideTitle(show.getSlides().get(1))).isEqualTo(title);
        }
    }

    @Test
    void manyShortLinesAtMaximumContentLengthStillWork() throws Exception {
        // Viele kurze Zeilen: 5.000 einstellige Zeilen (LF) ergeben ca. 335 Folien. Das ist NICHT der absolute
        // Worst Case: Auch leere Zeilen belegen das Zeilenbudget pro Folie, daher ergeben z. B. 9.999 LF plus ein
        // Buchstabe (per Skript, innerhalb von @Size(10000)) ca. 668 Folien. Browser senden Zeilenumbrueche als
        // CRLF (2 Zeichen), Formulareingaben kommen daher auf hoechstens ca. 335 Folien. Eine Obergrenze fuer
        // die Folienanzahl ist eine spaetere Produktentscheidung (bewusst kein geaendertes Verhalten hier).
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            lines.add(Integer.toString(i % 10));
        }
        String content = String.join("\n", lines);
        assertThat(content.length()).isLessThan(10_000);

        byte[] pptx = generator.generate(new FileContentRequest("Viele Zeilen", null, content));

        try (XMLSlideShow show = open(pptx)) {
            assertThat(show.getSlides()).hasSize(1 + (int) Math.ceil(5_000 / (double) PptxGenerator.MAX_LINES_PER_SLIDE));
            assertThat(allBodyParagraphs(show)).containsExactlyElementsOf(lines);
        }
    }

    @Test
    void fallsBackToPlainTextBoxesWhenLayoutsAreMissing() throws Exception {
        PptxGenerator withoutLayouts = new PptxGenerator() {
            @Override
            XSLFSlideLayout findLayout(XMLSlideShow slideShow, SlideLayout type) {
                return null;
            }
        };

        byte[] pptx = withoutLayouts.generate(new FileContentRequest("Titel", "Untertitel", "A\n\nB"));

        try (XMLSlideShow show = open(pptx)) {
            assertThat(show.getSlides()).hasSize(2);
            List<XSLFTextShape> titleShapes = textShapes(show.getSlides().get(0));
            assertThat(titleShapes).extracting(XSLFTextShape::getText).containsExactly("Titel", "Untertitel");
            assertThat(titleShapes).allSatisfy(shape -> assertThat(shape.isPlaceholder()).isFalse());
            assertThat(slideTitle(show.getSlides().get(1))).isEqualTo("Titel");
            assertThat(bodyParagraphs(show.getSlides().get(1))).containsExactly("A", "", "B");
            assertThat(textShapes(show.getSlides().get(1))).allSatisfy(shape ->
                    assertThat(shape.isPlaceholder()).isFalse());
        }
        assertPassivePackage(pptx);
    }

    @Test
    void isStatelessAcrossRepeatedCalls() throws Exception {
        byte[] first = generator.generate(new FileContentRequest("Eins", null, "a"));
        byte[] second = generator.generate(new FileContentRequest("Zwei", null, "b\nc"));
        try (XMLSlideShow one = open(first); XMLSlideShow two = open(second)) {
            assertThat(one.getSlides()).hasSize(2);
            assertThat(allBodyParagraphs(one)).containsExactly("a");
            assertThat(two.getSlides()).hasSize(2);
            assertThat(allBodyParagraphs(two)).containsExactly("b", "c");
        }
    }

    @Test
    void documentPropertiesAreHonestAndCurrent() throws Exception {
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        byte[] pptx = generator.generate(new FileContentRequest("Metadaten", "Sub", "Zeile\n".repeat(20).trim()));
        Instant after = Instant.now();

        Map<String, String> entries = zipEntries(pptx);
        String app = entries.get("docProps/app.xml");
        String core = entries.get("docProps/core.xml");
        assertThat(app).isNotNull();
        assertThat(core).isNotNull();

        try (XMLSlideShow show = open(pptx)) {
            int slideCount = show.getSlides().size();
            assertThat(slideCount).isEqualTo(3);

            // Erweiterte Eigenschaften: nur Anwendung + echte Folienanzahl, keine veralteten Vorlagenwerte.
            assertThat(app).contains("<Application>" + PptxGenerator.APPLICATION_NAME + "</Application>")
                    .contains("<Slides>" + slideCount + "</Slides>")
                    .doesNotContain("Microsoft")
                    .doesNotContain("AppVersion")
                    .doesNotContain("Company")
                    .doesNotContain("Manager")
                    .doesNotContain("Template")
                    .doesNotContain("TitlesOfParts")
                    .doesNotContain("HeadingPairs")
                    .doesNotContain("Words")
                    .doesNotContain("Paragraphs")
                    .doesNotContain("TotalTime")
                    .doesNotContain("PresentationFormat");
            assertThat(show.getProperties().getExtendedProperties().getApplication())
                    .isEqualTo(PptxGenerator.APPLICATION_NAME);
            assertThat(show.getProperties().getExtendedProperties().getSlides()).isEqualTo(slideCount);

            // Kerneigenschaften: aktuelle Zeitpunkte statt 2006/2011, kein lastModifiedBy, keine Revision.
            assertThat(core).doesNotContain("2006-08-16")
                    .doesNotContain("2011-08-01")
                    .doesNotContain("lastModifiedBy")
                    .doesNotContain("revision");
            var coreProperties = show.getProperties().getCoreProperties();
            assertThat(coreProperties.getCreated().toInstant()).isBetween(before, after);
            assertThat(coreProperties.getModified().toInstant()).isBetween(before, after);
            assertThat(coreProperties.getCreator()).isEqualTo(PptxGenerator.APPLICATION_NAME);
            assertThat(coreProperties.getLastModifiedByUser()).isNull();
            assertThat(coreProperties.getTitle()).isEqualTo("Metadaten");
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Sicherheit auf ZIP-Ebene
    // ------------------------------------------------------------------------------------------------

    @Test
    void packageContainsNothingActiveOrExternal() throws Exception {
        byte[] pptx = generator.generate(new FileContentRequest(
                "Titel mit https://example.invalid/login",
                "Untertitel www.example.invalid",
                "Inhalt mit http://example.invalid/pfad als reiner Text\nmailto:someone@example.invalid"));
        assertPassivePackage(pptx);
    }

    @Test
    void urlsInTextStayPlainText() throws Exception {
        byte[] pptx = generator.generate(new FileContentRequest("T", null, "Siehe http://example.invalid/x"));
        try (XMLSlideShow show = open(pptx)) {
            assertThat(allBodyParagraphs(show)).containsExactly("Siehe http://example.invalid/x");
            for (XSLFSlide slide : show.getSlides()) {
                for (XSLFTextShape shape : textShapes(slide)) {
                    for (XSLFTextParagraph paragraph : shape.getTextParagraphs()) {
                        paragraph.getTextRuns().forEach(run -> assertThat(run.getHyperlink()).isNull());
                    }
                }
            }
        }
    }

    @Test
    void packageOfLongContentContainsNothingActiveOrExternal() throws Exception {
        byte[] pptx = generator.generate(new FileContentRequest("T", "S", "Zeile\n".repeat(1_000)));
        assertPassivePackage(pptx);
    }

    /** Gemeinsame ZIP-Pruefung: Die Praesentation ist ausschliesslich passiv. */
    private static void assertPassivePackage(byte[] pptx) throws Exception {
        Map<String, String> entries = zipEntries(pptx);
        assertThat(entries).isNotEmpty();

        // Keine Makros, keine eingebetteten Objekte/OLE/ActiveX, keine Medien, keine Bilder (auch kein Thumbnail).
        assertThat(entries.keySet()).doesNotContain("ppt/vbaProject.bin");
        assertThat(entries.keySet()).allSatisfy(name -> {
            String lower = name.toLowerCase(Locale.ROOT);
            assertThat(lower).doesNotContain("vbaproject")
                    .doesNotContain("embeddings")
                    .doesNotContain("oleobject")
                    .doesNotContain("activex")
                    .doesNotContain("media")
                    .doesNotEndWith(".bin")
                    .doesNotEndWith(".jpeg")
                    .doesNotEndWith(".jpg")
                    .doesNotEndWith(".png")
                    .doesNotEndWith(".gif")
                    .doesNotEndWith(".emf")
                    .doesNotEndWith(".wmf");
        });

        // Mindestens eine Folie vorhanden, keine Hyperlinks in Folien.
        List<String> slideNames = entries.keySet().stream()
                .filter(name -> name.matches("ppt/slides/slide\\d+\\.xml"))
                .toList();
        assertThat(slideNames).isNotEmpty();
        slideNames.forEach(name -> assertThat(entries.get(name)).doesNotContain("hlink"));

        // Nirgends Klick-/Mouseover-Aktionen, OLE- oder Steuerelement-Referenzen.
        entries.forEach((name, text) -> {
            if (name.endsWith(".xml")) {
                assertThat(text).doesNotContain("hlinkClick")
                        .doesNotContain("hlinkMouseOver")
                        .doesNotContain("oleObj")
                        .doesNotContain("r:embed")
                        .doesNotContain("r:link");
            }
        });

        // Keine externen Relationships (TargetMode="External").
        entries.forEach((name, text) -> {
            if (name.endsWith(".rels")) {
                assertThat(text).doesNotContain("TargetMode=\"External\"")
                        .doesNotContain("External")
                        .doesNotContain("hyperlink")
                        .doesNotContain("oleObject")
                        .doesNotContain("vbaProject");
            }
        });

        // Content-Type ist eine normale (nicht makrofaehige) Praesentation.
        String contentTypes = entries.get("[Content_Types].xml");
        assertThat(contentTypes).isNotBlank();
        assertThat(contentTypes).contains("presentationml.presentation.main+xml");
        assertThat(contentTypes.toLowerCase(Locale.ROOT)).doesNotContain("macroenabled");
        assertThat(contentTypes.toLowerCase(Locale.ROOT)).doesNotContain("image/");
    }
}
