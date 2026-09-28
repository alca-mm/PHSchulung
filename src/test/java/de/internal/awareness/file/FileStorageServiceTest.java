package de.internal.awareness.file;

import de.internal.awareness.config.FileStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FileStorageService} gegen ein {@link TempDir}: interner UUID-Name, Speicherung AUSSCHLIESSLICH im
 * konfigurierten Verzeichnis, Groessenlimit und Path-Traversal-Schutz beim Lesen.
 *
 * <p>Die parametrisierten Tests am Ende pruefen Round-Trip, Endung, Verzeichnis-Eingrenzung und Groessenlimit
 * zusaetzlich fuer die neuen passiven Dateitypen (PDF/XLSX/PPTX/TXT/CSV) - mit rohen Test-Bytes, unabhaengig
 * von den Generatoren.</p>
 */
class FileStorageServiceTest {

    @TempDir
    Path tempDir;

    private FileStorageService storage;

    @BeforeEach
    void setUp() {
        FileStorageProperties props = new FileStorageProperties();
        props.setGeneratedDir(tempDir.toString());
        props.setMaxFileSizeBytes(1024);
        storage = new FileStorageService(props);
    }

    @Test
    void storesFileOnlyWithinConfiguredDirectoryAndReadsItBack() {
        byte[] content = "Hallo Welt".getBytes(StandardCharsets.UTF_8);

        String stored = storage.store(content, GeneratedFileType.DOCX);

        assertThat(stored).endsWith(".docx");
        assertThat(storage.exists(stored)).isTrue();
        // Die Datei liegt direkt im konfigurierten Basisverzeichnis (nicht anderswo).
        Path written = storage.baseDir().resolve(stored);
        assertThat(written.getParent()).isEqualTo(storage.baseDir());
        assertThat(storage.read(stored)).isEqualTo(content);
    }

    @Test
    void baseDirIsTheConfiguredDirectory() {
        assertThat(storage.baseDir()).isEqualTo(tempDir.toAbsolutePath().normalize());
    }

    @Test
    void enforcesSizeLimit() {
        byte[] tooBig = new byte[1025];
        assertThatThrownBy(() -> storage.store(tooBig, GeneratedFileType.XML))
                .isInstanceOf(FileTooLargeException.class);
    }

    @Test
    void readOfUnknownFileFails() {
        assertThatThrownBy(() -> storage.read("does-not-exist.docx"))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void rejectsTraversalOnRead() {
        assertThatThrownBy(() -> storage.read("../evil.docx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.read("sub/dir.docx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.read("a\\b.docx"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteQuietlyRemovesFile() {
        String stored = storage.store("x".getBytes(StandardCharsets.UTF_8), GeneratedFileType.XML);
        assertThat(storage.exists(stored)).isTrue();
        storage.deleteQuietly(stored);
        assertThat(storage.exists(stored)).isFalse();
    }

    // --- Neue passive Dateitypen (PDF/XLSX/PPTX/TXT/CSV) ---

    /** Rohe Test-Bytes inkl. aller 256 Bytewerte (prueft binaere Unversehrtheit), passend zum Typ eingeleitet. */
    private static byte[] sampleContent(GeneratedFileType type) {
        byte[] prefix = ("Passiver Trainingsinhalt " + type.name() + " - Umlaute: \u00e4\u00f6\u00fc\u00df\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[prefix.length + 256];
        System.arraycopy(prefix, 0, content, 0, prefix.length);
        for (int i = 0; i < 256; i++) {
            content[prefix.length + i] = (byte) i;
        }
        return content;
    }

    /** Namen aller Eintraege im Basisverzeichnis. */
    private List<String> filesInBaseDir() throws IOException {
        try (Stream<Path> entries = Files.list(storage.baseDir())) {
            return entries.map(p -> p.getFileName().toString()).toList();
        }
    }

    @ParameterizedTest
    @EnumSource(value = GeneratedFileType.class, names = {"PDF", "XLSX", "PPTX", "TXT", "CSV"})
    void storesAndReadsBackNewTypesOnlyWithinConfiguredDirectory(GeneratedFileType type) throws IOException {
        byte[] content = sampleContent(type);

        String stored = storage.store(content, type);

        // Interner Name: UUID + genau die Typ-Endung (keine Benutzereingabe).
        assertThat(stored).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\."
                + Pattern.quote(type.extension()));
        assertThat(storage.exists(stored)).isTrue();
        // Die Datei liegt direkt im konfigurierten Basisverzeichnis (nicht anderswo) ...
        Path written = storage.baseDir().resolve(stored).normalize();
        assertThat(written.getParent()).isEqualTo(storage.baseDir());
        assertThat(written.startsWith(tempDir.toAbsolutePath().normalize())).isTrue();
        assertThat(Files.isRegularFile(written)).isTrue();
        // ... und ist die einzige geschriebene Datei.
        assertThat(filesInBaseDir()).containsExactly(stored);
        // Round-Trip byte-identisch (auch binaere Bytes und UTF-8-Umlaute).
        assertThat(storage.read(stored)).isEqualTo(content);
        assertThat(Files.readAllBytes(written)).isEqualTo(content);
    }

    @ParameterizedTest
    @EnumSource(value = GeneratedFileType.class, names = {"PDF", "XLSX", "PPTX", "TXT", "CSV"})
    void enforcesSizeLimitForNewTypesAndWritesNothing(GeneratedFileType type) throws IOException {
        byte[] tooBig = new byte[1025];

        assertThatThrownBy(() -> storage.store(tooBig, type))
                .isInstanceOf(FileTooLargeException.class);
        // Fail-closed: bei Ueberschreitung wird nichts geschrieben.
        assertThat(filesInBaseDir()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = GeneratedFileType.class, names = {"PDF", "XLSX", "PPTX", "TXT", "CSV"})
    void acceptsContentExactlyAtSizeLimitForNewTypes(GeneratedFileType type) {
        byte[] atLimit = new byte[1024];

        String stored = storage.store(atLimit, type);

        assertThat(stored).endsWith("." + type.extension());
        assertThat(storage.read(stored)).hasSize(1024);
    }

    @ParameterizedTest
    @EnumSource(value = GeneratedFileType.class, names = {"PDF", "XLSX", "PPTX", "TXT", "CSV"})
    void rejectsTraversalOnReadWithNewTypeExtensions(GeneratedFileType type) {
        String ext = "." + type.extension();
        assertThatThrownBy(() -> storage.read("../evil" + ext))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.read("sub/dir" + ext))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.read("a\\b" + ext))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
