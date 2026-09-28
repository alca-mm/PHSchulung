package de.internal.awareness.file;

import de.internal.awareness.config.FileStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Speichert und liest die Inhalte erzeugter, passiver Dateien im konfigurierten lokalen Datenverzeichnis.
 *
 * <p>Sicherheit:</p>
 * <ul>
 *   <li>Der physische Dateiname wird intern per UUID vergeben ({@link SafeFileNames#newStoredFilename}) -
 *       niemals aus einer HTTP-Anfrage oder Benutzereingabe.</li>
 *   <li>Jeder Zugriff wird auf das Basisverzeichnis eingegrenzt: der aufgeloeste, normalisierte Pfad muss
 *       innerhalb des Basisverzeichnisses liegen (Schutz gegen Path-Traversal, defense-in-depth).</li>
 *   <li>Ein Groessenlimit ({@link FileStorageProperties#getMaxFileSizeBytes()}) verhindert riesige Dateien.</li>
 * </ul>
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final Path baseDir;
    private final long maxFileSizeBytes;

    public FileStorageService(FileStorageProperties properties) {
        this.baseDir = Path.of(properties.getGeneratedDir()).toAbsolutePath().normalize();
        this.maxFileSizeBytes = properties.getMaxFileSizeBytes();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Verzeichnis fuer erzeugte Dateien konnte nicht angelegt werden.", e);
        }
        log.info("Dateibibliothek-Verzeichnis: {}", baseDir);
    }

    /** Das (absolute, normalisierte) Basisverzeichnis fuer erzeugte Dateien. */
    public Path baseDir() {
        return baseDir;
    }

    /**
     * Speichert den Inhalt unter einem NEU erzeugten, internen (UUID-)Dateinamen und gibt diesen Namen
     * zurueck. Der Inhalt darf das konfigurierte Groessenlimit nicht ueberschreiten.
     *
     * @param content der Datei-Inhalt
     * @param type    der Dateityp (bestimmt die Endung des physischen Namens)
     * @return der physische (gespeicherte) Dateiname
     * @throws FileTooLargeException wenn der Inhalt das Limit ueberschreitet
     */
    public String store(byte[] content, GeneratedFileType type) {
        if (content == null) {
            throw new IllegalArgumentException("Kein Datei-Inhalt.");
        }
        if (content.length > maxFileSizeBytes) {
            throw new FileTooLargeException(content.length, maxFileSizeBytes);
        }
        String storedFilename = SafeFileNames.newStoredFilename(type);
        Path target = resolveWithinBase(storedFilename);
        try {
            // CREATE_NEW: der UUID-Name darf nicht bereits existieren (praktisch unmoeglich, aber fail-closed).
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Datei konnte nicht gespeichert werden.", e);
        }
        return storedFilename;
    }

    /** Liest den Inhalt einer gespeicherten Datei anhand ihres internen Namens. */
    public byte[] read(String storedFilename) {
        Path target = resolveWithinBase(storedFilename);
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Datei konnte nicht gelesen werden.", e);
        }
    }

    /** Prueft, ob die gespeicherte Datei physisch existiert. */
    public boolean exists(String storedFilename) {
        return Files.isRegularFile(resolveWithinBase(storedFilename));
    }

    /** Loescht die gespeicherte Datei (fuer Cleanup nach fehlgeschlagener Metadaten-Persistenz). */
    public void deleteQuietly(String storedFilename) {
        try {
            Files.deleteIfExists(resolveWithinBase(storedFilename));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Aufraeumen einer Datei fehlgeschlagen (Name={}).", storedFilename);
        }
    }

    /**
     * Loest einen Dateinamen relativ zum Basisverzeichnis auf und stellt sicher, dass das Ergebnis INNERHALB
     * des Basisverzeichnisses liegt. Lehnt Pfadtrenner/Traversal/absolute Pfade ab (defense-in-depth, obwohl
     * der interne Name eine UUID ist).
     */
    private Path resolveWithinBase(String storedFilename) {
        if (storedFilename == null || storedFilename.isBlank()) {
            throw new IllegalArgumentException("Dateiname fehlt.");
        }
        if (storedFilename.indexOf('/') >= 0 || storedFilename.indexOf('\\') >= 0
                || storedFilename.contains("..") || storedFilename.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Ungueltiger Dateiname.");
        }
        Path resolved = baseDir.resolve(storedFilename).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Dateipfad liegt ausserhalb des erlaubten Verzeichnisses.");
        }
        return resolved;
    }
}
