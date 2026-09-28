-- Flyway V5: Metadaten-Tabelle fuer die Dateibibliothek (erzeugte, PASSIVE Trainingsdateien).
--
-- Additive Migration; V1-V4 bleiben unveraendert (Flyway-Checksumme). Es werden ausschliesslich METADATEN
-- gespeichert - der eigentliche Datei-Inhalt liegt als Datei im lokalen Datenverzeichnis
-- (app.files.generated-dir), nicht in der Datenbank. Der physische Dateiname (stored_filename) wird intern
-- (UUID) vergeben; es werden NIE benutzergelieferte Pfade uebernommen.
--
-- Typen/Laengen entsprechen dem, was Hibernate (Community-SQLiteDialect) fuer das Mapping GeneratedFile
-- erzeugen wuerde: integer fuer die IDENTITY-Id (rowid-Alias), varchar(n) gemaess @Column(length),
-- bigint fuer die Dateigroesse (long), timestamp fuer Instant. Die Ausrichtung sichert EntitySchemaAlignmentTest.
--
-- file_type traegt eine CHECK-Constraint mit exakt allen Enum-Konstanten (DOCX/XML); ein neuer Typ
-- erfordert eine neue Migration (Tabellen-Neuaufbau).

CREATE TABLE generated_file (
    id                integer,
    display_name      varchar(255)  NOT NULL,
    stored_filename   varchar(255)  NOT NULL,
    download_filename varchar(255)  NOT NULL,
    file_type         varchar(32)   NOT NULL CHECK (file_type IN ('DOCX', 'XML')),
    content_type      varchar(255)  NOT NULL,
    file_size         bigint        NOT NULL,
    created_at        timestamp     NOT NULL,
    PRIMARY KEY (id)
);

-- Eindeutigkeit des physischen (UUID-)Dateinamens auf DB-Ebene. Der Name muss zum @Index(unique=true)
-- auf GeneratedFile passen (Hibernate validiert benannte Unique-Keys: unique_key_validation=NAMED).
CREATE UNIQUE INDEX uk_generated_file_stored_filename ON generated_file (stored_filename);
