package de.internal.awareness.file;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Individualisierung einer DOCX-Versandkopie mit sichtbarem Trainingslink: Original unveraendert, sichtbarer
 * Hyperlink enthalten, unterschiedliche Links je Empfaenger, weiterhin KEIN Makro, und die EINZIGE externe
 * Relationship ist genau dieser sichtbare Hyperlink (kein Auto-Open/Remote-Image/automatischer Netzwerkzugriff).
 */
class DocxTrackingPersonalizerTest {

    private static final String URL_A = "https://training.example.invalid/t/AaAaAaAa-_11111111111111111111111";
    private static final String URL_B = "https://training.example.invalid/t/BbBbBbBb-_22222222222222222222222";

    private final DocxGenerator generator = new DocxGenerator();
    private final DocxTrackingPersonalizer personalizer = new DocxTrackingPersonalizer();

    private byte[] original() {
        return generator.generate("Rechnung", "September", "Sehr geehrte Damen und Herren");
    }

    private static String docxText(byte[] docx) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

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

    private static String relsConcatenated(byte[] docx) throws Exception {
        StringBuilder all = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().endsWith(".rels")) {
                    all.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
                }
            }
        }
        return all.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    void originalBytesAreNotModified() {
        byte[] original = original();
        byte[] snapshot = original.clone();
        personalizer.withTrainingLink(original, URL_A);
        assertThat(original).isEqualTo(snapshot);
    }

    @Test
    void copyContainsVisibleHyperlink() throws Exception {
        byte[] out = personalizer.withTrainingLink(original(), URL_A);
        assertThat(docxText(out)).contains(URL_A);
        String rels = relsConcatenated(out);
        assertThat(rels).contains("TargetMode=\"External\"");
        assertThat(rels).contains(URL_A);
        assertThat(rels.toLowerCase(Locale.ROOT)).contains("hyperlink");
    }

    @Test
    void differentRecipientsGetDifferentLinks() throws Exception {
        String a = docxText(personalizer.withTrainingLink(original(), URL_A));
        String b = docxText(personalizer.withTrainingLink(original(), URL_B));
        assertThat(a).contains(URL_A);
        assertThat(b).contains(URL_B);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void copyContainsNoMacro() throws Exception {
        List<String> names = zipEntryNames(personalizer.withTrainingLink(original(), URL_A));
        assertThat(names).noneSatisfy(n -> assertThat(n.toLowerCase(Locale.ROOT)).contains("vbaproject"));
        assertThat(names).noneSatisfy(n -> assertThat(n.toLowerCase(Locale.ROOT)).endsWith(".bin"));
    }

    @Test
    void theOnlyExternalRelationshipIsTheVisibleHyperlink() throws Exception {
        byte[] out = personalizer.withTrainingLink(original(), URL_A);
        String rels = relsConcatenated(out);
        // Genau eine externe Relationship, und diese zeigt auf die Trainingslink-URL.
        assertThat(countOccurrences(rels, "TargetMode=\"External\"")).isEqualTo(1);
        assertThat(rels).contains(URL_A);
        // Kein Remote-Bild / kein externes Template (nur der sichtbare Hyperlink ist extern).
        assertThat(rels.toLowerCase(Locale.ROOT)).doesNotContain("attachedtemplate");
    }
}
