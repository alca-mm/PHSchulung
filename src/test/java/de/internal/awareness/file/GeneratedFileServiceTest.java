package de.internal.awareness.file;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * {@link GeneratedFileService} gegen die reale (isolierte) SQLite-Test-DB und ein Temp-Datenverzeichnis
 * (app.files.generated-dir wird auf ein {@link TempDir} umgebogen). Prueft Erzeugung/Speicherung/Metadaten
 * fuer DOCX und XML, das Laden des Inhalts, die Sortierung, unbekannte IDs und die Ablehnung unsicherer
 * Dateinamen (inkl. Cleanup / keine verwaisten Metadaten). Zusaetzlich wird der generische Einstieg
 * {@link GeneratedFileService#create} fuer die weiteren passiven Typen (PDF/XLSX/PPTX/TXT/CSV) gegen die realen
 * Generator-Beans geprueft.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class GeneratedFileServiceTest {

    @TempDir
    static Path generatedDir;

    @DynamicPropertySource
    static void fileProps(DynamicPropertyRegistry registry) {
        registry.add("app.files.generated-dir", () -> generatedDir.toString());
    }

    @Autowired
    private GeneratedFileService service;

    @Autowired
    private GeneratedFileRepository repository;

    @Test
    void createsDocxWithMetadataAndContent() throws Exception {
        GeneratedFile file = service.createDocx("Rechnung September", "rechnung-september",
                "Rechnung", "September 2026", "Sehr geehrte Damen und Herren,\nBetrag: 100 EUR.");

        assertThat(file.getId()).isNotNull();
        assertThat(file.getDisplayName()).isEqualTo("Rechnung September");
        assertThat(file.getDownloadFilename()).isEqualTo("rechnung-september.docx");
        assertThat(file.getFileType()).isEqualTo(GeneratedFileType.DOCX);
        assertThat(file.getContentType()).isEqualTo(GeneratedFileType.DOCX.contentType());
        assertThat(file.getFileSize()).isPositive();

        byte[] content = service.loadContent(file);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            assertThat(extractor.getText()).contains("Rechnung").contains("Betrag: 100 EUR.");
        }
    }

    @Test
    void createsXmlWithMetadataAndContent() {
        GeneratedFile file = service.createXml("Datenexport", "datenexport",
                "daten", "Export", "Nutzdaten");

        assertThat(file.getDownloadFilename()).isEqualTo("datenexport.xml");
        assertThat(file.getFileType()).isEqualTo(GeneratedFileType.XML);
        assertThat(file.getContentType()).isEqualTo(GeneratedFileType.XML.contentType());

        String xml = new String(service.loadContent(file), StandardCharsets.UTF_8);
        assertThat(xml).startsWith("<?xml").contains("<daten>").contains("Nutzdaten");
    }

    @Test
    void listsNewestFirst() {
        GeneratedFile first = service.createXml("A", "a", "r", "t", "c");
        GeneratedFile second = service.createXml("B", "b", "r", "t", "c");

        List<GeneratedFile> all = service.findAll();
        assertThat(all).extracting(GeneratedFile::getId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void getByIdUnknownThrowsNotFound() {
        assertThatThrownBy(() -> service.getById(999_999L))
                .isInstanceOf(GeneratedFileNotFoundException.class);
    }

    @Test
    void rejectsUnsafeFileNameAndPersistsNothing() {
        long before = repository.count();
        assertThatThrownBy(() -> service.createDocx("Boese", "../../etc/passwd", "T", null, "B"))
                .isInstanceOf(IllegalArgumentException.class);
        // Keine verwaisten Metadaten (der Name wird VOR dem Speichern bereinigt/abgelehnt).
        assertThat(repository.count()).isEqualTo(before);
    }

    // --- Generischer Einstieg create(...) fuer die weiteren passiven Dateitypen ---

    /** Eindeutige Textmarke im Inhalt (Umlaut per Unicode-Escape, damit der Quelltext ASCII bleibt). */
    private static final String MARKER_TEXT = "Quartals\u00fcbersicht";

    private static Set<String> storedFiles() throws IOException {
        try (Stream<Path> files = Files.list(generatedDir)) {
            return files.map(f -> f.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    @ParameterizedTest
    @EnumSource(value = GeneratedFileType.class, names = {"PDF", "XLSX", "PPTX", "TXT", "CSV"})
    void createPersistsNewTypeWithMetadataAndReadableContent(GeneratedFileType type) {
        GeneratedFile file = service.create(type, "Bericht Q3", "bericht-q3",
                new FileContentRequest("Bericht", "Drittes Quartal", "Region\tUmsatz\n" + MARKER_TEXT + "\t100"));

        assertThat(file.getId()).isNotNull();
        assertThat(file.getDisplayName()).isEqualTo("Bericht Q3");
        assertThat(file.getFileType()).isEqualTo(type);
        assertThat(file.getContentType()).isEqualTo(type.contentType());
        assertThat(file.getDownloadFilename()).isEqualTo("bericht-q3." + type.extension());
        assertThat(file.getStoredFilename()).endsWith("." + type.extension()).doesNotContain("bericht");

        byte[] content = service.loadContent(file);
        assertThat(file.getFileSize()).isEqualTo(content.length);
        GeneratedContentAssertions.assertReadableContent(type, content, MARKER_TEXT);

        GeneratedFile reloaded = repository.findById(file.getId()).orElseThrow();
        assertThat(reloaded.getFileType()).isEqualTo(type);
        assertThat(reloaded.getContentType()).isEqualTo(type.contentType());
    }

    @Test
    void createDocxViaGenericCreateBehavesLikeCreateDocx() {
        GeneratedFile classic = service.createDocx("Rechnung", "rechnung", "Titel", "Untertitel", "Betrag: 100 EUR.");
        GeneratedFile generic = service.create(GeneratedFileType.DOCX, "Rechnung", "rechnung",
                new FileContentRequest("Titel", "Untertitel", "Betrag: 100 EUR."));

        assertThat(generic.getFileType()).isEqualTo(classic.getFileType()).isEqualTo(GeneratedFileType.DOCX);
        assertThat(generic.getContentType()).isEqualTo(classic.getContentType());
        assertThat(generic.getDownloadFilename()).isEqualTo(classic.getDownloadFilename()).isEqualTo("rechnung.docx");
        assertThat(generic.getStoredFilename()).isNotEqualTo(classic.getStoredFilename());
        assertThat(GeneratedContentAssertions.docxText(service.loadContent(generic)))
                .isEqualTo(GeneratedContentAssertions.docxText(service.loadContent(classic)))
                .contains("Titel").contains("Untertitel").contains("Betrag: 100 EUR.");
    }

    @Test
    void createXmlViaGenericCreateUsesDefaultRoot() {
        GeneratedFile file = service.create(GeneratedFileType.XML, "Export", "export",
                new FileContentRequest("Titel", "ignoriert", "Nutzdaten"));

        assertThat(file.getFileType()).isEqualTo(GeneratedFileType.XML);
        assertThat(file.getDownloadFilename()).isEqualTo("export.xml");
        String xml = new String(service.loadContent(file), StandardCharsets.UTF_8);
        assertThat(xml).startsWith("<?xml").contains("<document>").contains("Nutzdaten").doesNotContain("ignoriert");
    }

    @Test
    void createWithNullTypeIsRejectedAndPersistsNothing() throws Exception {
        long before = repository.count();
        Set<String> filesBefore = storedFiles();

        assertThatThrownBy(() -> service.create(null, "Anzeige", "datei", new FileContentRequest("T", null, "B")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Dateityp fehlt.");

        assertThat(repository.count()).isEqualTo(before);
        assertThat(storedFiles()).isEqualTo(filesBefore);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> unsafeNamesForNewTypes() {
        List<String> unsafeNames = List.of("../../etc/passwd", "..\\evil", "C:evil", "a/b");
        return Stream.of(GeneratedFileType.PDF, GeneratedFileType.XLSX, GeneratedFileType.PPTX,
                        GeneratedFileType.TXT, GeneratedFileType.CSV)
                .flatMap(type -> unsafeNames.stream().map(name -> arguments(type, name)));
    }

    @ParameterizedTest
    @MethodSource("unsafeNamesForNewTypes")
    void createRejectsUnsafeFileNameForNewTypesWithoutOrphans(GeneratedFileType type, String unsafeName)
            throws Exception {
        long before = repository.count();
        Set<String> filesBefore = storedFiles();

        assertThatThrownBy(() -> service.create(type, "Boese", unsafeName, new FileContentRequest("T", null, "B")))
                .isInstanceOf(IllegalArgumentException.class);

        // Keine verwaisten Metadaten und keine verwaiste Datei im Datenverzeichnis.
        assertThat(repository.count()).isEqualTo(before);
        assertThat(storedFiles()).isEqualTo(filesBefore);
    }
}
