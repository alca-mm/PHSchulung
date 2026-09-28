package de.internal.awareness.file;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AttachmentPersonalizer} fuer die neuen passiven Dateitypen (PDF/XLSX/PPTX/TXT/CSV): Fuer diese Typen
 * findet bewusst KEINE Anhang-Personalisierung statt - die Original-Bytes werden unveraendert zurueckgegeben,
 * es wird kein Trainingslink eingefuegt und keiner der DOCX-/XML-Personalizer aufgerufen. Das Eingabe-Array
 * bleibt unveraendert.
 *
 * <p>Reiner Unit-Test ohne Spring: die realen Personalizer werden in zaehlende Unterklassen gekapselt. Ein
 * (faelschlicher) Aufruf mit Nicht-DOCX-/Nicht-XML-Bytes wuerde zudem mit einer Ausnahme scheitern.</p>
 */
class AttachmentPersonalizerFileTypesTest {

    /** Zaehlt Aufrufe des DOCX-Personalizers (delegiert an die reale Implementierung). */
    private static final class CountingDocxPersonalizer extends DocxTrackingPersonalizer {
        private int calls;

        @Override
        public byte[] withTrainingLink(byte[] originalDocx, String url) {
            calls++;
            return super.withTrainingLink(originalDocx, url);
        }
    }

    /** Zaehlt Aufrufe des XML-Personalizers (delegiert an die reale Implementierung). */
    private static final class CountingXmlPersonalizer extends XmlTrackingPersonalizer {
        private int calls;

        @Override
        public byte[] withTrainingLink(byte[] originalXml, String url) {
            calls++;
            return super.withTrainingLink(originalXml, url);
        }
    }

    /** Verschiedene (bereits validierte) Trainingslinks, u. a. mit Sonderzeichen fuer XML/CSV. */
    private static final List<String> LINKS = List.of(
            "https://training.example.invalid/t/AbC-_0123456789ABCDEFabcdefghij0123456789ab",
            "http://localhost:8080/t/zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz",
            "https://training.example.invalid/t/x?a=1&b=<c>;\"d\",e");

    private final CountingDocxPersonalizer docxPersonalizer = new CountingDocxPersonalizer();
    private final CountingXmlPersonalizer xmlPersonalizer = new CountingXmlPersonalizer();
    private final AttachmentPersonalizer personalizer = new AttachmentPersonalizer(docxPersonalizer, xmlPersonalizer);

    /** Typisch eingeleitete, rohe Test-Bytes je Typ (inkl. binaerer Bytes); unabhaengig von den Generatoren. */
    private static byte[] sampleContent(GeneratedFileType type) {
        String text = switch (type) {
            case PDF -> "%PDF-1.7\n% passives Trainingsdokument (Testbytes)\n";
            case XLSX, PPTX -> "PK\u0003\u0004 passive Office-Testbytes " + type.name() + "\n";
            case TXT -> "Hinweis zur Schulung - Umlaute: \u00e4\u00f6\u00fc\u00df\n";
            case CSV -> "Name;Abteilung\n\u00c4rger;IT\n'=SUMME(A1);Test\n";
            default -> throw new IllegalArgumentException("Nur neue Typen: " + type);
        };
        byte[] prefix = text.getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[prefix.length + 256];
        System.arraycopy(prefix, 0, content, 0, prefix.length);
        for (int i = 0; i < 256; i++) {
            content[prefix.length + i] = (byte) i;
        }
        return content;
    }

    /** Kreuzprodukt neue Typen x Trainingslinks. */
    static Stream<Arguments> newTypesWithLinks() {
        return Stream.of(GeneratedFileType.PDF, GeneratedFileType.XLSX, GeneratedFileType.PPTX,
                        GeneratedFileType.TXT, GeneratedFileType.CSV)
                .flatMap(type -> LINKS.stream().map(link -> Arguments.of(type, link)));
    }

    @ParameterizedTest
    @MethodSource("newTypesWithLinks")
    void returnsOriginalBytesUnchangedForNewPassiveTypes(GeneratedFileType type, String link) {
        byte[] original = sampleContent(type);
        byte[] snapshot = original.clone();

        byte[] result = personalizer.personalize(type, original, link);

        // Inhalt byte-identisch zum Original; das Eingabe-Array wurde nicht veraendert.
        assertThat(result).isEqualTo(snapshot);
        assertThat(original).isEqualTo(snapshot);
        // Kein Trainingslink/Token eingefuegt.
        String asText = new String(result, StandardCharsets.ISO_8859_1);
        assertThat(asText).doesNotContain(link).doesNotContain("/t/").doesNotContain("trainingLink");
        // Keiner der typ-spezifischen Personalizer wurde aufgerufen.
        assertThat(docxPersonalizer.calls).isZero();
        assertThat(xmlPersonalizer.calls).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = GeneratedFileType.class, names = {"PDF", "XLSX", "PPTX", "TXT", "CSV"})
    void returnsEmptyContentUnchangedForNewPassiveTypes(GeneratedFileType type) {
        byte[] result = personalizer.personalize(type, new byte[0], LINKS.get(0));

        assertThat(result).isEmpty();
        assertThat(docxPersonalizer.calls).isZero();
        assertThat(xmlPersonalizer.calls).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = GeneratedFileType.class, names = {"PDF", "XLSX", "PPTX", "TXT", "CSV"})
    void repeatedCallsWithDifferentLinksYieldIdenticalContent(GeneratedFileType type) {
        byte[] original = sampleContent(type);

        // Je Empfaenger ein anderer Link - der Anhang bleibt fuer alle identisch (keine Individualisierung).
        byte[] first = personalizer.personalize(type, original, LINKS.get(0));
        byte[] second = personalizer.personalize(type, original, LINKS.get(1));

        assertThat(first).isEqualTo(second).isEqualTo(sampleContent(type));
    }

    @Test
    void docxAndXmlAreStillPersonalizedByTheirDedicatedPersonalizers() throws IOException {
        // Gegenprobe (Regression): die bestehenden Typen DOCX/XML werden weiterhin individualisiert. Die
        // Eingaben werden bewusst ohne die Generatoren erzeugt (unabhaengig von deren Weiterentwicklung).
        String link = LINKS.get(0);
        byte[] docx = minimalDocx();
        byte[] xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><daten><titel>Titel</titel></daten>"
                .getBytes(StandardCharsets.UTF_8);

        byte[] docxResult = personalizer.personalize(GeneratedFileType.DOCX, docx, link);
        byte[] xmlResult = personalizer.personalize(GeneratedFileType.XML, xml, link);

        assertThat(docxPersonalizer.calls).isEqualTo(1);
        assertThat(xmlPersonalizer.calls).isEqualTo(1);
        assertThat(docxText(docxResult)).contains(link);
        assertThat(new String(xmlResult, StandardCharsets.UTF_8))
                .contains("<trainingLink>" + link + "</trainingLink>");
    }

    /** Minimales, passives DOCX direkt ueber Apache POI (ohne DocxGenerator). */
    private static byte[] minimalDocx() throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Inhalt");
            document.write(out);
            return out.toByteArray();
        }
    }

    private static String docxText(byte[] docx) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }
}
