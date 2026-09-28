package de.internal.awareness.file;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Anwendungsdienst der Dateibibliothek: erzeugt passive Trainingsdateien (DOCX, XML, PDF, XLSX, PPTX, TXT, CSV),
 * speichert Inhalt (Dateisystem) und Metadaten (Datenbank), listet die Bibliothek und laedt Inhalte fuer den
 * Download bzw. den E-Mail-Anhang.
 *
 * <p>Generator-Auswahl: Alle {@link FileContentGenerator}-Beans werden beim Start in eine {@link EnumMap} je
 * {@link GeneratedFileType} eingesammelt. Der Aufbau schlaegt fail-fast mit {@link IllegalStateException} fehl,
 * wenn fuer einen Typ mehrere Generatoren existieren, ein Generator keinen Typ meldet oder fuer irgendeinen
 * {@link GeneratedFileType} KEIN Generator vorhanden ist - so kann kein neuer Typ ohne passenden Generator
 * ausgeliefert werden und nie still in einem anderen Format landen.</p>
 *
 * <p>Ablauf beim Erzeugen: Inhalt generieren -&gt; Datei speichern (interner UUID-Name) -&gt; Metadaten
 * persistieren. Schlaegt die Metadaten-Persistenz fehl, wird die bereits geschriebene Datei wieder
 * aufgeraeumt (kein verwaister Inhalt). Der Download-Name wird zuvor streng bereinigt
 * ({@link SafeFileNames#safeDownloadFilename}); der physische Name enthaelt nie Benutzereingaben.</p>
 */
@Service
@Transactional(readOnly = true)
public class GeneratedFileService {

    private final GeneratedFileRepository repository;
    private final FileStorageService storageService;
    private final XmlGenerator xmlGenerator;
    private final Map<GeneratedFileType, FileContentGenerator> generators;

    /**
     * @param repository     Metadaten-Repository
     * @param storageService Ablage der Datei-Inhalte
     * @param xmlGenerator   XML-Generator fuer {@link #createXml} (benoetigt zusaetzlich den Root-Namen)
     * @param generators     alle {@link FileContentGenerator}-Beans; genau einer je {@link GeneratedFileType}
     * @throws IllegalStateException bei doppeltem, typlosem oder fehlendem Generator (fail-fast beim Start)
     */
    public GeneratedFileService(GeneratedFileRepository repository,
                                FileStorageService storageService,
                                XmlGenerator xmlGenerator,
                                List<FileContentGenerator> generators) {
        this.repository = repository;
        this.storageService = storageService;
        this.xmlGenerator = xmlGenerator;
        this.generators = indexByType(generators);
    }

    /**
     * Baut die Zuordnung Typ -&gt; Generator auf und prueft sie streng: jeder {@link GeneratedFileType} braucht
     * genau einen Generator. Die Meldungen nennen nur Typ-Namen (keine Benutzerdaten).
     */
    private static Map<GeneratedFileType, FileContentGenerator> indexByType(List<FileContentGenerator> generators) {
        Map<GeneratedFileType, FileContentGenerator> byType = new EnumMap<>(GeneratedFileType.class);
        if (generators != null) {
            for (FileContentGenerator generator : generators) {
                GeneratedFileType type = generator.type();
                if (type == null) {
                    throw new IllegalStateException("Dateigenerator ohne Dateityp: "
                            + generator.getClass().getSimpleName());
                }
                if (byType.putIfAbsent(type, generator) != null) {
                    throw new IllegalStateException("Mehrere Dateigeneratoren fuer Dateityp " + type + ".");
                }
            }
        }
        List<GeneratedFileType> missing = new ArrayList<>();
        for (GeneratedFileType type : GeneratedFileType.values()) {
            if (!byType.containsKey(type)) {
                missing.add(type);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Kein Dateigenerator fuer Dateityp(en): " + missing + ".");
        }
        return byType;
    }

    /** Alle Dateien der Bibliothek, neueste zuerst. */
    public List<GeneratedFile> findAll() {
        return repository.findAllByOrderByCreatedAtDescIdDesc();
    }

    /** Anzahl der Dateien (fuer die Anhang-Auswahl im Composer). */
    public long count() {
        return repository.count();
    }

    /** Laedt eine Datei-Metadatenzeile oder wirft {@link GeneratedFileNotFoundException}. */
    public GeneratedFile getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new GeneratedFileNotFoundException(id));
    }

    /**
     * Generischer Einstieg: erzeugt eine passive Datei des angegebenen Typs ueber den zugehoerigen
     * {@link FileContentGenerator}, speichert sie und legt die Metadaten an. Fuer XML wird dabei das
     * Standard-Wurzelelement verwendet; mit eigenem Root-Namen siehe {@link #createXml}.
     *
     * @param type        Dateityp (Pflicht; kein Rueckfall auf ein Standardformat)
     * @param displayName Anzeigename in der Bibliothek (Pflicht)
     * @param fileName    gewuenschter Basis-Dateiname (wird bereinigt, Endung wird anhand des Typs ergaenzt)
     * @param request     neutrales Eingabemodell (Titel/Untertitel/Text)
     * @throws IllegalArgumentException bei fehlendem/nicht unterstuetztem Typ, fehlendem Anzeigenamen oder
     *                                  unsicherem Dateinamen (Meldungen ohne interne Details)
     * @throws FileTooLargeException    wenn der erzeugte Inhalt das Groessenlimit ueberschreitet
     */
    @Transactional
    public GeneratedFile create(GeneratedFileType type, String displayName, String fileName,
                                FileContentRequest request) {
        if (type == null) {
            throw new IllegalArgumentException("Dateityp fehlt.");
        }
        FileContentGenerator generator = generators.get(type);
        if (generator == null) {
            // Defensiv/fail-closed: durch die Startpruefung praktisch unerreichbar - nie ein anderes Format erzeugen.
            throw new IllegalArgumentException("Dateityp wird nicht unterstuetzt.");
        }
        // Guenstige Eingabepruefungen VOR der (bei PDF/PPTX ggf. aufwendigen) Generierung.
        String downloadFilename = validatedDownloadFilename(displayName, fileName, type);
        byte[] content = generator.generate(request);
        return storeAndPersist(displayName, downloadFilename, type, content);
    }

    /**
     * Erzeugt ein passives DOCX, speichert es und legt die Metadaten an (delegiert an
     * {@link #create} mit {@link GeneratedFileType#DOCX}).
     *
     * @param displayName Anzeigename in der Bibliothek (Pflicht)
     * @param fileName    gewuenschter Basis-Dateiname (wird bereinigt, Endung wird ergaenzt)
     * @param title       Dokumenttitel
     * @param subtitle    optionaler Untertitel
     * @param body        Freitext
     */
    @Transactional
    public GeneratedFile createDocx(String displayName, String fileName, String title, String subtitle, String body) {
        return create(GeneratedFileType.DOCX, displayName, fileName, new FileContentRequest(title, subtitle, body));
    }

    /**
     * Erzeugt eine sichere, selbstenthaltende XML-Datei, speichert sie und legt die Metadaten an.
     *
     * @param displayName Anzeigename in der Bibliothek (Pflicht)
     * @param fileName    gewuenschter Basis-Dateiname (wird bereinigt, Endung wird ergaenzt)
     * @param rootName    gewuenschter Name des Wurzelelements (wird bereinigt)
     * @param title       Titel-Inhalt
     * @param content     Text-Inhalt
     */
    @Transactional
    public GeneratedFile createXml(String displayName, String fileName, String rootName, String title, String content) {
        String downloadFilename = validatedDownloadFilename(displayName, fileName, GeneratedFileType.XML);
        byte[] xml = xmlGenerator.generate(rootName, title, content);
        return storeAndPersist(displayName, downloadFilename, GeneratedFileType.XML, xml);
    }

    /** Laedt den Datei-Inhalt (fuer Download/Anhang) anhand des Metadatensatzes. */
    public byte[] loadContent(GeneratedFile file) {
        return storageService.read(file.getStoredFilename());
    }

    /**
     * Prueft den Anzeigenamen und bereinigt den gewuenschten Download-Namen streng (siehe
     * {@link SafeFileNames#safeDownloadFilename}). Wird VOR der Inhaltserzeugung aufgerufen, damit unzulaessige
     * Eingaben ohne unnoetige Generierung abgelehnt werden.
     */
    private static String validatedDownloadFilename(String displayName, String fileName, GeneratedFileType type) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Anzeigename fehlt.");
        }
        return SafeFileNames.safeDownloadFilename(fileName, type);
    }

    /**
     * Gemeinsamer Kern: Inhalt speichern (interner UUID-Name), Metadaten persistieren. Anzeige- und
     * Download-Name wurden zuvor ueber {@link #validatedDownloadFilename} geprueft. Bei einem Fehler waehrend der
     * Persistenz wird die bereits geschriebene Datei wieder geloescht (kein verwaister Inhalt).
     */
    private GeneratedFile storeAndPersist(String displayName, String downloadFilename, GeneratedFileType type,
                                          byte[] content) {
        String storedFilename = storageService.store(content, type);
        try {
            GeneratedFile metadata = new GeneratedFile(displayName.trim(), storedFilename, downloadFilename,
                    type, type.contentType(), content.length);
            return repository.saveAndFlush(metadata);
        } catch (RuntimeException e) {
            // Metadaten-Persistenz fehlgeschlagen -> gespeicherte Datei aufraeumen, damit kein verwaister
            // Inhalt zurueckbleibt. Der urspruengliche Fehler wird weitergereicht.
            storageService.deleteQuietly(storedFilename);
            throw e;
        }
    }
}
