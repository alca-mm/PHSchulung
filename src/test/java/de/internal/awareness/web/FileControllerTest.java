package de.internal.awareness.web;

import de.internal.awareness.file.GeneratedFile;
import de.internal.awareness.file.GeneratedFileService;
import de.internal.awareness.file.GeneratedFileType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * MVC-Tests der Dateibibliothek-Oberflaeche ({@link FileController}). Voller Stack ueber MockMvc gegen die
 * isolierte SQLite-DB; das Datei-Datenverzeichnis wird auf ein {@link TempDir} umgebogen, damit die Tests
 * nie das echte {@code ./data}-Verzeichnis beruehren. Prueft Uebersicht, Formular, Erzeugung (DOCX/XML),
 * Download (inkl. Content-Disposition/Content-Type), 404 bei unbekannter ID sowie die Ablehnung unsicherer
 * Dateinamen und leerer Pflichtfelder.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.test.context.TestExecutionListeners(listeners = org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener.class, mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@WithMockUser(username = "test-admin", roles = "ADMIN")
class FileControllerTest {

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

    @Test
    void fileOverviewWorks() throws Exception {
        mockMvc.perform(get("/files"))
                .andExpect(status().isOk())
                .andExpect(view().name("files/list"))
                .andExpect(content().string(containsString("Dateien")));
    }

    @Test
    void fileCreateFormWorks() throws Exception {
        mockMvc.perform(get("/files/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("files/new"))
                .andExpect(content().string(containsString("Dateiname")));
    }

    @Test
    void createDocxRedirectsAndPersists() throws Exception {
        mockMvc.perform(post("/files").with(csrf())
                        .param("displayName", "Rechnung September")
                        .param("fileName", "rechnung-september")
                        .param("type", "DOCX")
                        .param("title", "Rechnung")
                        .param("body", "Sehr geehrte Damen und Herren,\nBetrag: 100 EUR."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files"))
                .andExpect(flash().attributeExists("flashSuccess"));

        List<GeneratedFile> all = fileService.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getDownloadFilename()).isEqualTo("rechnung-september.docx");
        assertThat(all.get(0).getFileType()).isEqualTo(GeneratedFileType.DOCX);
    }

    @Test
    void createXmlRedirectsAndPersists() throws Exception {
        mockMvc.perform(post("/files").with(csrf())
                        .param("displayName", "Datenexport")
                        .param("fileName", "datenexport")
                        .param("type", "XML")
                        .param("rootName", "daten")
                        .param("title", "Export")
                        .param("body", "Nutzdaten"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files"))
                .andExpect(flash().attributeExists("flashSuccess"));

        List<GeneratedFile> all = fileService.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getDownloadFilename()).isEqualTo("datenexport.xml");
        assertThat(all.get(0).getFileType()).isEqualTo(GeneratedFileType.XML);
    }

    @Test
    void downloadReturnsAttachmentWithCorrectHeaders() throws Exception {
        GeneratedFile file = fileService.createDocx("Rechnung September", "rechnung-september",
                "Rechnung", "September 2026", "Betrag: 100 EUR.");

        MvcResult result = mockMvc.perform(get("/files/{id}/download", file.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString(file.getDownloadFilename())))
                .andReturn();

        assertThat(result.getResponse().getContentType()).contains(GeneratedFileType.DOCX.contentType());
        assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty();
    }

    @Test
    void downloadUnknownIdReturnsNotFound() throws Exception {
        mockMvc.perform(get("/files/{id}/download", 999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void createWithUnsafeFileNameIsRejectedAndPersistsNothing() throws Exception {
        mockMvc.perform(post("/files").with(csrf())
                        .param("displayName", "Boese Datei")
                        .param("fileName", "../evil")
                        .param("type", "DOCX")
                        .param("title", "T")
                        .param("body", "B"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/new"))
                .andExpect(flash().attributeExists("flashError"));

        assertThat(fileService.findAll()).isEmpty();
    }

    @Test
    void createWithBlankDisplayNameShowsValidationError() throws Exception {
        mockMvc.perform(post("/files").with(csrf())
                        .param("displayName", "")
                        .param("fileName", "datei")
                        .param("type", "DOCX")
                        .param("title", "T")
                        .param("body", "B"))
                .andExpect(status().isOk())
                .andExpect(view().name("files/new"));

        assertThat(fileService.findAll()).isEmpty();
    }
}
