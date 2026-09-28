package de.internal.awareness.web;

import de.internal.awareness.file.GeneratedFileService;
import de.internal.awareness.file.GeneratedFileType;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Das Groessenlimit der Dateibibliothek ({@code app.files.max-file-size-bytes}) greift fuer JEDEN Dateityp -
 * auch fuer die neuen Typen PDF/XLSX/PPTX/TXT/CSV. Eigener Spring-Kontext mit sehr kleinem Limit (200 Bytes):
 * Eine zu grosse Datei wird kontrolliert abgelehnt (Flash-Fehler), es entstehen weder Metadaten noch eine Datei
 * im (leeren) Datenverzeichnis.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.test.context.TestExecutionListeners(listeners = org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener.class, mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@WithMockUser(username = "test-admin", roles = "ADMIN")
class FileSizeLimitFileTypesTest {

    @TempDir
    static Path generatedDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.files.generated-dir", () -> generatedDir.toString());
        registry.add("app.files.max-file-size-bytes", () -> "200");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GeneratedFileService fileService;

    @ParameterizedTest
    @EnumSource(GeneratedFileType.class)
    void tooLargeFileIsRejectedForEveryTypeWithoutPersistingOrWriting(GeneratedFileType type) throws Exception {
        // Deutlich mehr als 200 Bytes Text - jedes Format ueberschreitet damit das Limit.
        String body = "Zeile mit Trainingsinhalt fuer das Groessenlimit\n".repeat(20);

        mockMvc.perform(post("/files").with(csrf())
                        .param("displayName", "Zu gross " + type.name())
                        .param("fileName", "zu-gross")
                        .param("type", type.name())
                        .param("rootName", "daten")
                        .param("title", "Titel")
                        .param("subtitle", "Untertitel")
                        .param("body", body))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/new"))
                .andExpect(flash().attribute("flashError", containsString("zu groß")));

        assertThat(fileService.findAll()).isEmpty();
        try (Stream<Path> files = Files.list(generatedDir)) {
            assertThat(files).isEmpty();
        }
    }
}
