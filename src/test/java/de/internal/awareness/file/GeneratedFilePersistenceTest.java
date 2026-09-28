package de.internal.awareness.file;

import de.internal.awareness.support.SqliteFlywayJpaTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistenz der Datei-Metadaten {@link GeneratedFile}: Speichern/Reload inkl. aller Felder, Enum-Mapping
 * des Dateityps, Eindeutigkeit des physischen (UUID-)Dateinamens und die Bibliotheks-Sortierung.
 *
 * <p>Zusaetzlich (parametrisiert): jeder der sieben Dateitypen laesst sich gegen die per Flyway migrierte
 * Test-DB speichern und neu laden (belegt, dass die V10-CHECK-Constraint alle Typen akzeptiert und das
 * Enum-Mapping als Name round-trippt); ein nicht katalogisierter Typ wird weiterhin von der CHECK abgelehnt.</p>
 */
@SqliteFlywayJpaTest
class GeneratedFilePersistenceTest {

    @Autowired
    private GeneratedFileRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private static GeneratedFile docx(String stored) {
        return new GeneratedFile("Rechnung September", stored, "rechnung-september.docx",
                GeneratedFileType.DOCX, GeneratedFileType.DOCX.contentType(), 12345L);
    }

    @Test
    void savesAndReloadsAllFields() {
        GeneratedFile saved = repository.saveAndFlush(docx("11111111-1111-1111-1111-111111111111.docx"));
        entityManager.clear();

        Optional<GeneratedFile> reloaded = repository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        GeneratedFile file = reloaded.get();
        assertThat(file.getDisplayName()).isEqualTo("Rechnung September");
        assertThat(file.getStoredFilename()).isEqualTo("11111111-1111-1111-1111-111111111111.docx");
        assertThat(file.getDownloadFilename()).isEqualTo("rechnung-september.docx");
        assertThat(file.getFileType()).isEqualTo(GeneratedFileType.DOCX);
        assertThat(file.getContentType()).isEqualTo(GeneratedFileType.DOCX.contentType());
        assertThat(file.getFileSize()).isEqualTo(12345L);
        assertThat(file.getCreatedAt()).isNotNull();
    }

    @Test
    void storesXmlType() {
        GeneratedFile xml = new GeneratedFile("Datenexport", "22222222-2222-2222-2222-222222222222.xml",
                "datenexport.xml", GeneratedFileType.XML, GeneratedFileType.XML.contentType(), 42L);
        GeneratedFile saved = repository.saveAndFlush(xml);
        entityManager.clear();

        assertThat(repository.findById(saved.getId()))
                .get().extracting(GeneratedFile::getFileType).isEqualTo(GeneratedFileType.XML);
    }

    @Test
    void listOrdersNewestFirst() {
        GeneratedFile first = repository.saveAndFlush(docx("aaaaaaaa-0000-0000-0000-000000000001.docx"));
        GeneratedFile second = repository.saveAndFlush(docx("aaaaaaaa-0000-0000-0000-000000000002.docx"));
        entityManager.clear();

        List<GeneratedFile> all = repository.findAllByOrderByCreatedAtDescIdDesc();
        assertThat(all).extracting(GeneratedFile::getId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void duplicateStoredFilenameIsRejected() {
        String stored = "33333333-3333-3333-3333-333333333333.docx";
        repository.saveAndFlush(docx(stored));

        // Der Unique-Index auf stored_filename lehnt das Duplikat auf DB-Ebene ab.
        assertThatThrownBy(() -> repository.saveAndFlush(docx(stored)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("UNIQUE constraint failed");
    }

    // --- Alle sieben Dateitypen (V10-CHECK + Enum-Mapping) ---

    @ParameterizedTest
    @EnumSource(GeneratedFileType.class)
    void persistsAndReloadsEveryFileType(GeneratedFileType type) {
        String stored = SafeFileNames.newStoredFilename(type);
        String download = SafeFileNames.safeDownloadFilename("trainingsdatei", type);
        GeneratedFile saved = repository.saveAndFlush(new GeneratedFile("Trainingsdatei " + type.name(), stored,
                download, type, type.contentType(), 2048L));
        entityManager.clear();

        GeneratedFile file = repository.findById(saved.getId()).orElseThrow();
        assertThat(file.getFileType()).isEqualTo(type);
        assertThat(file.getContentType()).isEqualTo(type.contentType());
        assertThat(file.getStoredFilename()).isEqualTo(stored).endsWith("." + type.extension());
        assertThat(file.getDownloadFilename()).isEqualTo("trainingsdatei." + type.extension());
        assertThat(file.getDisplayName()).isEqualTo("Trainingsdatei " + type.name());
        assertThat(file.getFileSize()).isEqualTo(2048L);
        assertThat(file.getCreatedAt()).isNotNull();

        // Rohwert in der Spalte: der Enum-NAME (EnumType.STRING), wie von der CHECK-Constraint erwartet.
        Object rawType = entityManager.getEntityManager()
                .createNativeQuery("SELECT file_type FROM generated_file WHERE id = ?1")
                .setParameter(1, saved.getId())
                .getSingleResult();
        assertThat(rawType).hasToString(type.name());
    }

    @Test
    void allSevenFileTypesCoexistInLibrary() {
        for (GeneratedFileType type : GeneratedFileType.values()) {
            repository.saveAndFlush(new GeneratedFile("Datei " + type.name(), SafeFileNames.newStoredFilename(type),
                    SafeFileNames.safeDownloadFilename("datei", type), type, type.contentType(), 1L));
        }
        entityManager.clear();

        assertThat(repository.findAllByOrderByCreatedAtDescIdDesc())
                .extracting(GeneratedFile::getFileType)
                .containsExactlyInAnyOrder(GeneratedFileType.values());
    }

    @Test
    void uncataloguedFileTypeIsStillRejectedByCheckConstraint() {
        // Die CHECK-Constraint ist weiterhin eine Allowlist: ein nicht katalogisierter (z. B. makrofaehiger)
        // Typ wird auf DB-Ebene abgelehnt. Damit ist belegt, dass die obigen Positivfaelle durch die V10-Liste
        // und nicht durch eine fehlende CHECK-Constraint zustande kommen.
        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO generated_file (display_name, stored_filename, download_filename, "
                        + "file_type, content_type, file_size, created_at) VALUES ('Makro', "
                        + "'44444444-4444-4444-4444-444444444444.xlsm', 'makro.xlsm', 'XLSM', "
                        + "'application/vnd.ms-excel.sheet.macroEnabled.12', 1, CURRENT_TIMESTAMP)")
                .executeUpdate())
                .hasStackTraceContaining("CHECK constraint failed");
    }
}
