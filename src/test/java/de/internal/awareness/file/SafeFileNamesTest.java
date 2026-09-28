package de.internal.awareness.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sichere Dateinamen: interner (UUID-)Name enthaelt keine Benutzereingaben; der Download-Name wird streng
 * bereinigt (Traversal/Pfade/Null-Byte/Steuerzeichen abgelehnt, korrekte Endung ergaenzt, keine Doppelendung).
 *
 * <p>Die parametrisierten Tests am Ende pruefen dasselbe Verhalten fuer die neuen passiven Dateitypen
 * (PDF/XLSX/PPTX/TXT/CSV) sowie den internen UUID-Namen fuer alle sieben Typen.</p>
 */
class SafeFileNamesTest {

    @Test
    void storedFilenameIsUuidWithExtensionAndUnique() {
        String a = SafeFileNames.newStoredFilename(GeneratedFileType.DOCX);
        String b = SafeFileNames.newStoredFilename(GeneratedFileType.DOCX);
        assertThat(a).endsWith(".docx").matches("[0-9a-fA-F-]{36}\\.docx");
        assertThat(a).isNotEqualTo(b);
        assertThat(SafeFileNames.newStoredFilename(GeneratedFileType.XML)).endsWith(".xml");
    }

    @Test
    void appendsCorrectExtension() {
        assertThat(SafeFileNames.safeDownloadFilename("rechnung-september", GeneratedFileType.DOCX))
                .isEqualTo("rechnung-september.docx");
        assertThat(SafeFileNames.safeDownloadFilename("datenexport", GeneratedFileType.XML))
                .isEqualTo("datenexport.xml");
    }

    @Test
    void doesNotProduceDoubleExtension() {
        assertThat(SafeFileNames.safeDownloadFilename("report.docx", GeneratedFileType.DOCX))
                .isEqualTo("report.docx");
        assertThat(SafeFileNames.safeDownloadFilename("report.txt", GeneratedFileType.XML))
                .isEqualTo("report.xml");
    }

    @Test
    void sanitizesDisallowedCharacters() {
        assertThat(SafeFileNames.safeDownloadFilename("a b*c", GeneratedFileType.DOCX))
                .isEqualTo("a-b-c.docx");
        assertThat(SafeFileNames.safeDownloadFilename("Rechnung  September", GeneratedFileType.DOCX))
                .isEqualTo("Rechnung-September.docx");
    }

    @Test
    void rejectsUnixPathTraversal() {
        assertThatThrownBy(() -> SafeFileNames.safeDownloadFilename("../../etc/passwd", GeneratedFileType.DOCX))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWindowsPathTraversal() {
        assertThatThrownBy(() -> SafeFileNames.safeDownloadFilename("..\\..\\windows\\system32", GeneratedFileType.DOCX))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAbsolutePaths() {
        assertThatThrownBy(() -> SafeFileNames.safeDownloadFilename("/etc/passwd", GeneratedFileType.XML))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeFileNames.safeDownloadFilename("C:temp", GeneratedFileType.XML))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullByteAndControlChars() {
        assertThatThrownBy(() -> SafeFileNames.safeDownloadFilename("a\u0000b", GeneratedFileType.DOCX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeFileNames.safeDownloadFilename("a\tb", GeneratedFileType.DOCX))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullBlankAndEmptyAfterSanitizing() {
        assertThatThrownBy(() -> SafeFileNames.safeDownloadFilename(null, GeneratedFileType.DOCX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeFileNames.safeDownloadFilename("   ", GeneratedFileType.DOCX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeFileNames.safeDownloadFilename("***", GeneratedFileType.DOCX))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsLength() {
        String veryLong = "a".repeat(500);
        String result = SafeFileNames.safeDownloadFilename(veryLong, GeneratedFileType.DOCX);
        // Basis auf 100 begrenzt + ".docx".
        assertThat(result).hasSize(105).endsWith(".docx");
    }

    // --- Neue passive Dateitypen (PDF/XLSX/PPTX/TXT/CSV) ---

    /** Unsichere Eingaben, die fuer JEDEN Dateityp abgelehnt werden muessen (Traversal/Pfade/Steuerzeichen). */
    private static final List<String> UNSAFE_INPUTS = List.of(
            "../../etc/passwd",
            "..\\..\\windows\\system32",
            "..",
            "bericht..pdf",
            "/etc/passwd",
            "\\\\server\\share\\datei",
            "C:temp",
            "C:\\temp\\datei",
            "unter/verzeichnis",
            "unter\\verzeichnis",
            "a\u0000b",
            "a\nb",
            "a\tb");

    /** Kreuzprodukt neue Typen x unsichere Eingaben (einzeln berichtet, damit Fehler eindeutig zuordenbar sind). */
    static Stream<Arguments> newTypesWithUnsafeInputs() {
        return Stream.of(GeneratedFileType.PDF, GeneratedFileType.XLSX, GeneratedFileType.PPTX,
                        GeneratedFileType.TXT, GeneratedFileType.CSV)
                .flatMap(type -> UNSAFE_INPUTS.stream().map(input -> Arguments.of(type, input)));
    }

    @ParameterizedTest
    @EnumSource(value = GeneratedFileType.class, names = {"PDF", "XLSX", "PPTX", "TXT", "CSV"})
    void appendsCorrectExtensionForNewTypes(GeneratedFileType type) {
        assertThat(SafeFileNames.safeDownloadFilename("schulung-2026", type))
                .isEqualTo("schulung-2026." + type.extension());
    }

    @ParameterizedTest
    @CsvSource({
            "rechnung.exe,  PDF,  rechnung.pdf",
            "daten.xlsm,    XLSX, daten.xlsx",
            "folien.pptm,   PPTX, folien.pptx",
            "notiz.docm,    TXT,  notiz.txt",
            "export.xls,    CSV,  export.csv",
            "bericht.pdf,   PDF,  bericht.pdf",
            "Bericht.PDF,   PDF,  Bericht.pdf",
            "tabelle.xlsx,  XLSX, tabelle.xlsx",
            "vortrag.pptx,  PPTX, vortrag.pptx",
            "hinweis.txt,   TXT,  hinweis.txt",
            "liste.csv,     CSV,  liste.csv"
    })
    void replacesUserSuppliedExtensionWithTypeExtensionForNewTypes(String raw, GeneratedFileType type,
                                                                   String expected) {
        // Eine vom Benutzer angegebene (fremde oder gleiche) Endung wird entfernt; es gilt immer die Typ-Endung
        // (keine Doppelendung, keine makrofaehige oder ausfuehrbare Endung).
        assertThat(SafeFileNames.safeDownloadFilename(raw, type)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(value = GeneratedFileType.class, names = {"PDF", "XLSX", "PPTX", "TXT", "CSV"})
    void sanitizesDisallowedCharactersForNewTypes(GeneratedFileType type) {
        assertThat(SafeFileNames.safeDownloadFilename("Rechnung  September*2026", type))
                .isEqualTo("Rechnung-September-2026." + type.extension());
    }

    @ParameterizedTest
    @EnumSource(value = GeneratedFileType.class, names = {"PDF", "XLSX", "PPTX", "TXT", "CSV"})
    void limitsLengthForNewTypes(GeneratedFileType type) {
        String result = SafeFileNames.safeDownloadFilename("b".repeat(500), type);
        // Basis auf 100 Zeichen begrenzt + "." + Typ-Endung.
        assertThat(result).hasSize(100 + 1 + type.extension().length()).endsWith("." + type.extension());
    }

    @ParameterizedTest
    @MethodSource("newTypesWithUnsafeInputs")
    void rejectsTraversalAbsolutePathsSeparatorsAndControlCharsForNewTypes(GeneratedFileType type, String input) {
        assertThatThrownBy(() -> SafeFileNames.safeDownloadFilename(input, type))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = GeneratedFileType.class, names = {"PDF", "XLSX", "PPTX", "TXT", "CSV"})
    void rejectsNullBlankAndEmptyAfterSanitizingForNewTypes(GeneratedFileType type) {
        assertThatThrownBy(() -> SafeFileNames.safeDownloadFilename(null, type))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeFileNames.safeDownloadFilename("   ", type))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeFileNames.safeDownloadFilename("***", type))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "DOCX, docx",
            "XML,  xml",
            "PDF,  pdf",
            "XLSX, xlsx",
            "PPTX, pptx",
            "TXT,  txt",
            "CSV,  csv"
    })
    void storedFilenameIsUuidPlusTypeExtensionForAllTypes(GeneratedFileType type, String expectedExtension) {
        String stored = SafeFileNames.newStoredFilename(type);

        assertThat(stored).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\."
                + Pattern.quote(expectedExtension));
        // Der Namensteil vor der Endung ist eine gueltige UUID (keine Benutzereingabe).
        String uuidPart = stored.substring(0, stored.length() - expectedExtension.length() - 1);
        assertThat(UUID.fromString(uuidPart).toString()).isEqualTo(uuidPart);
        // Jeder Aufruf vergibt einen neuen Namen.
        assertThat(SafeFileNames.newStoredFilename(type)).isNotEqualTo(stored);
    }

    @Test
    void storedFilenameCoversEveryDeclaredType() {
        // Schutz gegen einen neuen Typ ohne Pruefung: der Katalog umfasst genau die sieben bekannten Typen, und
        // jeder liefert einen internen Namen mit genau seiner Endung.
        assertThat(GeneratedFileType.values()).containsExactly(GeneratedFileType.DOCX, GeneratedFileType.XML,
                GeneratedFileType.PDF, GeneratedFileType.XLSX, GeneratedFileType.PPTX, GeneratedFileType.TXT,
                GeneratedFileType.CSV);
        for (GeneratedFileType type : GeneratedFileType.values()) {
            assertThat(SafeFileNames.newStoredFilename(type)).endsWith("." + type.extension());
        }
    }
}
