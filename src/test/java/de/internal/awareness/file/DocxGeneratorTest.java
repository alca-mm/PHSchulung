package de.internal.awareness.file;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sicherheits- und Korrektheitstests fuer {@link DocxGenerator}: gueltiges Office-Open-XML, enthaltener
 * Text, KEINE Makro-Datei (vbaProject.bin), KEINE externen Relationships.
 */
class DocxGeneratorTest {

    private final DocxGenerator generator = new DocxGenerator();

    private static List<String> zipEntryNames(byte[] docx) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static String entryContent(byte[] docx, String suffix) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entry;
            StringBuilder all = new StringBuilder();
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().endsWith(suffix)) {
                    all.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
                }
            }
            return all.toString();
        }
    }

    @Test
    void producesNonEmptyDocx() {
        byte[] docx = generator.generate("Titel", null, "Inhalt");
        assertThat(docx).isNotEmpty();
        // ZIP-Signatur "PK".
        assertThat(docx[0]).isEqualTo((byte) 'P');
        assertThat(docx[1]).isEqualTo((byte) 'K');
    }

    @Test
    void producesValidOfficeOpenXmlThatOpens() throws Exception {
        byte[] docx = generator.generate("Rechnung September", "Untertitel", "Zeile A\nZeile B");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            assertThat(extractor.getText()).contains("Rechnung September");
        }
    }

    @Test
    void containsEnteredText() throws Exception {
        byte[] docx = generator.generate("MeinTitel", "MeinUntertitel", "EindeutigerKoerpertext123");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            assertThat(text).contains("MeinTitel");
            assertThat(text).contains("MeinUntertitel");
            assertThat(text).contains("EindeutigerKoerpertext123");
        }
    }

    @Test
    void containsNoMacroFile() throws Exception {
        byte[] docx = generator.generate("Titel", null, "Inhalt");
        List<String> names = zipEntryNames(docx);
        assertThat(names).noneSatisfy(name ->
                assertThat(name.toLowerCase(java.util.Locale.ROOT)).contains("vbaproject"));
        assertThat(names).noneSatisfy(name ->
                assertThat(name.toLowerCase(java.util.Locale.ROOT)).endsWith(".bin"));
    }

    @Test
    void contentTypesAreNotMacroEnabled() throws Exception {
        byte[] docx = generator.generate("Titel", null, "Inhalt");
        String contentTypes = entryContent(docx, "[Content_Types].xml");
        assertThat(contentTypes).isNotBlank();
        assertThat(contentTypes.toLowerCase(java.util.Locale.ROOT)).doesNotContain("macroenabled");
    }

    @Test
    void handlesControlCharactersInInput() throws Exception {
        // In XML 1.0 unzulaessige Steuerzeichen duerfen das OOXML nicht kaputt machen; sie werden entfernt.
        byte[] docx = generator.generate("Titel\u0000X", "Sub\u0008Y", "Zeile\u0001Eins");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            assertThat(text).contains("TitelX").contains("ZeileEins");
        }
    }

    @Test
    void hasNoExternalRelationships() throws Exception {
        byte[] docx = generator.generate("Titel", "Untertitel", "Inhalt mit http://example.invalid als reiner Text");
        String rels = entryContent(docx, ".rels");
        // Externe Relationships waeren an TargetMode="External" erkennbar - es darf keine geben.
        assertThat(rels).doesNotContain("TargetMode=\"External\"");
        assertThat(rels).doesNotContain("External");
    }

    @Test
    void implementsFileContentGeneratorForDocx() throws Exception {
        FileContentGenerator contentGenerator = generator;
        assertThat(contentGenerator.type()).isEqualTo(GeneratedFileType.DOCX);

        byte[] docx = contentGenerator.generate(
                new FileContentRequest("AnfrageTitel", "AnfrageUntertitel", "AnfrageText\nZweite Zeile"));
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            assertThat(extractor.getText())
                    .contains("AnfrageTitel")
                    .contains("AnfrageUntertitel")
                    .contains("AnfrageText")
                    .contains("Zweite Zeile");
        }
    }

    @Test
    void requestBasedGenerateEqualsClassicGenerateInText() throws Exception {
        byte[] viaRequest = generator.generate(new FileContentRequest("T", "U", "Inhalt"));
        byte[] classic = generator.generate("T", "U", "Inhalt");
        try (XWPFDocument a = new XWPFDocument(new ByteArrayInputStream(viaRequest));
             XWPFWordExtractor ea = new XWPFWordExtractor(a);
             XWPFDocument b = new XWPFDocument(new ByteArrayInputStream(classic));
             XWPFWordExtractor eb = new XWPFWordExtractor(b)) {
            assertThat(ea.getText()).isEqualTo(eb.getText());
        }
    }

    @Test
    void requestBasedGenerateRejectsNullRequest() {
        assertThatThrownBy(() -> generator.generate((FileContentRequest) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Kein Inhalt.");
    }
}
