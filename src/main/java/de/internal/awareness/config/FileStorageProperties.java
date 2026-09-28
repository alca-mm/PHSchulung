package de.internal.awareness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration der Dateibibliothek (Prefix {@code app.files}).
 *
 * <p>Die erzeugten, passiven Trainingsdokumente werden als Metadaten in der Datenbank und als Datei-Inhalt
 * im lokalen Datenverzeichnis abgelegt - bewusst NICHT im Repository, nicht unter {@code src/main/resources}
 * und nicht unter {@code target}. Der physische Dateiname wird intern (UUID) vergeben; benutzergelieferte
 * Pfade werden niemals uebernommen.</p>
 */
@ConfigurationProperties(prefix = "app.files")
public class FileStorageProperties {

    /**
     * Basisverzeichnis fuer erzeugte Dateien. Default {@code ./data/generated-files}. Wird beim Start bei
     * Bedarf angelegt. Physische Dateinamen sind UUID-basiert; es wird immer nur innerhalb dieses
     * Verzeichnisses gelesen/geschrieben (Path-Traversal-Schutz in FileStorageService).
     */
    private String generatedDir = "./data/generated-files";

    /**
     * Obergrenze fuer die Groesse einer einzelnen erzeugten Datei in Bytes. Default 5 MiB. Generierte
     * passive Dokumente (DOCX/XML/PDF/XLSX/PPTX/TXT/CSV) sind normalerweise deutlich kleiner; die Grenze
     * verhindert versehentlich riesige Dateien.
     */
    private long maxFileSizeBytes = 5L * 1024 * 1024;

    public String getGeneratedDir() {
        return generatedDir;
    }

    public void setGeneratedDir(String generatedDir) {
        this.generatedDir = generatedDir;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }
}
