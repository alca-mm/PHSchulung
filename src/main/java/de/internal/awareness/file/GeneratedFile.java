package de.internal.awareness.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Metadaten einer erzeugten, PASSIVEN Trainingsdatei. Der eigentliche Datei-Inhalt liegt NICHT in der
 * Datenbank, sondern als Datei im konfigurierten Datenverzeichnis (siehe FileStorageService); hier stehen
 * ausschliesslich Metadaten.
 *
 * <p>Sicherheit: {@code storedFilename} ist ein intern vergebener (UUID-basierter) physischer Dateiname -
 * niemals ein vom Benutzer gelieferter Pfad. {@code downloadFilename} ist der benutzerfreundliche Name fuer
 * den Download (Content-Disposition) und wurde vorab bereinigt (kein {@code ../}, keine Pfadtrenner,
 * kein Null-Byte). Es werden keine Dateisystempfade gespeichert.</p>
 */
@Entity
@Table(
        name = "generated_file",
        // Unique-Index wird von Flyway (V5) angelegt und in V10 beim Tabellen-Neuaufbau mit identischem Namen neu
        // erzeugt; Hibernate validiert ihn (unique_key_validation=NAMED).
        indexes = @Index(name = "uk_generated_file_stored_filename", columnList = "stored_filename", unique = true)
)
public class GeneratedFile {

    // Siehe Campaign#id: INTEGER-Mapping fuer SQLite-IDENTITY (rowid-Alias) + Hibernate-Validierung.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JdbcTypeCode(SqlTypes.INTEGER)
    private Long id;

    /** Anzeigename in der Bibliothek (z. B. "Rechnung September"). */
    @NotBlank
    @Column(name = "display_name", nullable = false)
    private String displayName;

    /** Intern vergebener physischer Dateiname (UUID + Endung). Global eindeutig (Unique-Index). */
    @NotBlank
    @Column(name = "stored_filename", nullable = false)
    private String storedFilename;

    /** Benutzerfreundlicher Downloadname inkl. korrekter Endung (bereinigt). */
    @NotBlank
    @Column(name = "download_filename", nullable = false)
    private String downloadFilename;

    /**
     * Dateityp (DOCX, XML, PDF, XLSX, PPTX, TXT oder CSV). Muss der CHECK-Constraint der Spalte
     * {@code file_type} entsprechen (Flyway V5, erweitert in V10).
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 32)
    private GeneratedFileType fileType;

    /** MIME-Content-Type fuer Download/Anhang. */
    @NotBlank
    @Column(name = "content_type", nullable = false)
    private String contentType;

    /** Dateigroesse in Bytes. */
    @PositiveOrZero
    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Fuer JPA. */
    protected GeneratedFile() {
    }

    public GeneratedFile(String displayName, String storedFilename, String downloadFilename,
                         GeneratedFileType fileType, String contentType, long fileSize) {
        this.displayName = displayName;
        this.storedFilename = storedFilename;
        this.downloadFilename = downloadFilename;
        this.fileType = fileType;
        this.contentType = contentType;
        this.fileSize = fileSize;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getStoredFilename() {
        return storedFilename;
    }

    public String getDownloadFilename() {
        return downloadFilename;
    }

    public GeneratedFileType getFileType() {
        return fileType;
    }

    public String getContentType() {
        return contentType;
    }

    public long getFileSize() {
        return fileSize;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
