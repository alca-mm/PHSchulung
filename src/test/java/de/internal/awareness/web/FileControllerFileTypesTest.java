package de.internal.awareness.web;

import de.internal.awareness.file.FileContentRequest;
import de.internal.awareness.file.GeneratedContentAssertions;
import de.internal.awareness.file.GeneratedFile;
import de.internal.awareness.file.GeneratedFileService;
import de.internal.awareness.file.GeneratedFileType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * MVC-Tests der Dateibibliothek fuer ALLE passiven Dateitypen (DOCX, XML, PDF, XLSX, PPTX, TXT, CSV).
 *
 * <p>Regressionsschutz fuer den frueheren Fehler, dass {@code FileController#create} jeden Nicht-DOCX-Typ still
 * als XML erzeugte: Jeder Typ muss ueber die Oberflaeche als genau dieser Typ (Metadaten, Endung, Content-Type,
 * Datei-Signatur) entstehen; unbekannte/gefaehrliche Typwerte werden kontrolliert abgelehnt, ohne dass Metadaten
 * oder Dateien entstehen. Zusaetzlich: Download-Header je Typ, Path-Traversal-Ablehnung und das Ersetzen einer
 * vom Benutzer angegebenen Endung. Das Datenverzeichnis zeigt auf ein {@link TempDir}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.test.context.TestExecutionListeners(listeners = org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener.class, mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@WithMockUser(username = "test-admin", roles = "ADMIN")
class FileControllerFileTypesTest {

    /** Eindeutige Textmarke mit Umlaut (als char-Konstante, damit der Quelltext ASCII bleibt). */
    private static final String MARKER = "Quartals" + (char) 0x00FC + "bersicht";

    /** Freitext mit zwei Zeilen und Tabulator-getrennten Spalten (fuer XLSX/CSV relevant). */
    private static final String BODY = "Region\tUmsatz\n" + MARKER + "\t100";

    @TempDir
    static Path generatedDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.files.generated-dir", () -> generatedDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GeneratedFileService fileService;

    private static Set<String> storedFiles() throws IOException {
        try (Stream<Path> files = Files.list(generatedDir)) {
            return files.map(f -> f.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    // --- Formular ---

    @Test
    void newFormOffersExactlyAllSupportedTypesInEnumOrder() throws Exception {
        String html = mockMvc.perform(get("/files/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("files/new"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        int previousIndex = -1;
        for (GeneratedFileType type : GeneratedFileType.values()) {
            int index = html.indexOf("value=\"" + type.name() + "\"");
            assertThat(index).as("Option fuer %s", type).isPositive();
            assertThat(index).as("Reihenfolge der Option %s", type).isGreaterThan(previousIndex);
            previousIndex = index;
        }
        // Genau eine Option je Typ - keine zusaetzlichen (z. B. makrofaehigen) Formate.
        assertThat(html.split("<option", -1)).hasSize(GeneratedFileType.values().length + 1);
        assertThat(html).contains("PDF (PDF-Dokument)")
                .contains("XLSX (Excel-Arbeitsmappe)")
                .contains("PPTX (PowerPoint-Präsentation)")
                .contains("TXT (Textdatei, UTF-8)")
                .contains("CSV (Tabelle, UTF-8)")
                .contains("Untertitel (nicht bei XML)");
        assertThat(html).doesNotContain("DOCM").doesNotContain("XLSM").doesNotContain("PPTM");
    }

    // --- Erzeugen ---

    @ParameterizedTest
    @EnumSource(value = GeneratedFileType.class, names = {"PDF", "XLSX", "PPTX", "TXT", "CSV"})
    void createNewTypeRedirectsAndPersistsExactlyThatType(GeneratedFileType type) throws Exception {
        mockMvc.perform(post("/files").with(csrf())
                        .param("displayName", "Trainingsdatei " + type.name())
                        .param("fileName", "trainingsdatei")
                        .param("type", type.name())
                        .param("rootName", "wirdIgnoriert")
                        .param("title", "Bericht")
                        .param("subtitle", "Drittes Quartal")
                        .param("body", BODY))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files"))
                .andExpect(flash().attributeExists("flashSuccess"));

        List<GeneratedFile> all = fileService.findAll();
        assertThat(all).hasSize(1);
        GeneratedFile file = all.get(0);
        assertThat(file.getFileType()).isEqualTo(type);
        assertThat(file.getDownloadFilename()).isEqualTo("trainingsdatei." + type.extension());
        assertThat(file.getContentType()).isEqualTo(type.contentType());
        assertThat(file.getStoredFilename()).endsWith("." + type.extension());

        byte[] stored = fileService.loadContent(file);
        assertThat(file.getFileSize()).isEqualTo(stored.length);
        // Signatur/Lesbarkeit je Typ und KEIN XML-Inhalt (frueherer stiller XML-Rueckfall).
        GeneratedContentAssertions.assertReadableContent(type, stored, MARKER);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotStartWith("<?xml");
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("wirdIgnoriert");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pdf", "Pdf", " PDF "})
    void typeValueIsMatchedCaseInsensitively(String typeValue) throws Exception {
        mockMvc.perform(post("/files").with(csrf())
                        .param("displayName", "Klein geschrieben")
                        .param("fileName", "klein")
                        .param("type", typeValue)
                        .param("title", "Titel")
                        .param("body", "Inhalt"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files"))
                .andExpect(flash().attributeExists("flashSuccess"));

        List<GeneratedFile> all = fileService.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getFileType()).isEqualTo(GeneratedFileType.PDF);
        assertThat(all.get(0).getDownloadFilename()).isEqualTo("klein.pdf");
        GeneratedContentAssertions.assertReadableContent(GeneratedFileType.PDF,
                fileService.loadContent(all.get(0)), "Inhalt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXE", "DOCM", "XLSM", "PPTM", "HTML", "JS", "../PDF", "PDF;XML", "XML PDF", "DOCX.EXE", "SVG"})
    void unknownTypeIsRejectedWithoutPersistingOrWritingFiles(String typeValue) throws Exception {
        Set<String> filesBefore = storedFiles();

        mockMvc.perform(post("/files").with(csrf())
                        .param("displayName", "Unbekannter Typ")
                        .param("fileName", "unbekannt")
                        .param("type", typeValue)
                        .param("rootName", "daten")
                        .param("title", "Titel")
                        .param("body", "Inhalt"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/new"))
                .andExpect(flash().attribute("flashError", "Datei konnte nicht erstellt werden: Unbekannter Dateityp."));

        assertThat(fileService.findAll()).isEmpty();
        assertThat(storedFiles()).isEqualTo(filesBefore);
    }

    @Test
    void blankTypeShowsValidationErrorAndPersistsNothing() throws Exception {
        Set<String> filesBefore = storedFiles();

        mockMvc.perform(post("/files").with(csrf())
                        .param("displayName", "Ohne Typ")
                        .param("fileName", "ohne-typ")
                        .param("type", "")
                        .param("title", "Titel")
                        .param("body", "Inhalt"))
                .andExpect(status().isOk())
                .andExpect(view().name("files/new"));

        assertThat(fileService.findAll()).isEmpty();
        assertThat(storedFiles()).isEqualTo(filesBefore);
    }

    @Test
    void xmlViaFormStillUsesRootNameAndIgnoresSubtitle() throws Exception {
        mockMvc.perform(post("/files").with(csrf())
                        .param("displayName", "Datenexport")
                        .param("fileName", "datenexport")
                        .param("type", "XML")
                        .param("rootName", "daten")
                        .param("title", "Export")
                        .param("subtitle", "KeinXmlFeld")
                        .param("body", "Nutzdaten"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files"));

        GeneratedFile file = fileService.findAll().get(0);
        assertThat(file.getFileType()).isEqualTo(GeneratedFileType.XML);
        String xml = GeneratedContentAssertions.strictUtf8(fileService.loadContent(file));
        assertThat(xml).startsWith("<?xml").contains("<daten>").contains("Export").contains("Nutzdaten")
                .doesNotContain("KeinXmlFeld");
    }

    @Test
    void docxViaFormStillUsesTitleSubtitleAndBody() throws Exception {
        mockMvc.perform(post("/files").with(csrf())
                        .param("displayName", "Rechnung")
                        .param("fileName", "rechnung")
                        .param("type", "DOCX")
                        .param("rootName", "wirdIgnoriert")
                        .param("title", "Rechnung")
                        .param("subtitle", "September 2026")
                        .param("body", "Betrag: 100 EUR."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files"));

        GeneratedFile file = fileService.findAll().get(0);
        assertThat(file.getFileType()).isEqualTo(GeneratedFileType.DOCX);
        assertThat(file.getDownloadFilename()).isEqualTo("rechnung.docx");
        String text = GeneratedContentAssertions.docxText(fileService.loadContent(file));
        assertThat(text).contains("Rechnung").contains("September 2026").contains("Betrag: 100 EUR.")
                .doesNotContain("wirdIgnoriert");
    }

    // --- Download ---

    @ParameterizedTest
    @EnumSource(GeneratedFileType.class)
    void downloadOfEveryTypeReturnsExactHeadersAndStoredBytes(GeneratedFileType type) throws Exception {
        // Alle 7 Typen (inkl. DOCX/XML als Regression): exakter Content-Type, immer als Anhang, nosniff.
        GeneratedFile file = fileService.create(type, "Download " + type.name(), "download-test",
                new FileContentRequest("Bericht", "Drittes Quartal", BODY));
        byte[] stored = fileService.loadContent(file);

        MvcResult result = mockMvc.perform(get("/files/{id}/download", file.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, type.contentType()))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"download-test." + type.extension() + "\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(stored);
    }

    // --- Dateinamen ---

    static Stream<Arguments> unsafeNamesForNewTypes() {
        List<String> unsafeNames = List.of("../../etc/passwd", "..\\evil", "C:evil", "a/b");
        return Stream.of(GeneratedFileType.PDF, GeneratedFileType.XLSX, GeneratedFileType.PPTX,
                        GeneratedFileType.TXT, GeneratedFileType.CSV)
                .flatMap(type -> unsafeNames.stream().map(name -> arguments(type, name)));
    }

    @ParameterizedTest
    @MethodSource("unsafeNamesForNewTypes")
    void unsafeFileNameIsRejectedForNewTypesAndPersistsNothing(GeneratedFileType type, String unsafeName)
            throws Exception {
        Set<String> filesBefore = storedFiles();

        mockMvc.perform(post("/files").with(csrf())
                        .param("displayName", "Boese Datei")
                        .param("fileName", unsafeName)
                        .param("type", type.name())
                        .param("title", "T")
                        .param("body", "B"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/new"))
                .andExpect(flash().attributeExists("flashError"));

        assertThat(fileService.findAll()).isEmpty();
        assertThat(storedFiles()).isEqualTo(filesBefore);
    }

    static Stream<Arguments> userExtensionIsReplaced() {
        return Stream.of(
                arguments("rechnung.exe", GeneratedFileType.TXT, "rechnung.txt"),
                arguments("dokument.js", GeneratedFileType.PDF, "dokument.pdf"),
                arguments("tabelle.xlsm", GeneratedFileType.XLSX, "tabelle.xlsx"),
                arguments("folien.pptm", GeneratedFileType.PPTX, "folien.pptx"),
                arguments("export.html", GeneratedFileType.CSV, "export.csv"));
    }

    @ParameterizedTest
    @MethodSource
    void userExtensionIsReplaced(String rawName, GeneratedFileType type, String expectedDownloadName)
            throws Exception {
        mockMvc.perform(post("/files").with(csrf())
                        .param("displayName", "Endung")
                        .param("fileName", rawName)
                        .param("type", type.name())
                        .param("title", "Titel")
                        .param("body", "Inhalt"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files"));

        GeneratedFile file = fileService.findAll().get(0);
        assertThat(file.getFileType()).isEqualTo(type);
        assertThat(file.getDownloadFilename()).isEqualTo(expectedDownloadName);
        assertThat(file.getStoredFilename()).endsWith("." + type.extension());
    }
}
