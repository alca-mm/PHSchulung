-- Flyway V10: file_type-CHECK der Dateibibliothek um weitere PASSIVE Dokumenttypen erweitern
-- (PDF, XLSX, PPTX, TXT, CSV) - zusaetzlich zu DOCX/XML.
--
-- V1-V9 bleiben unveraendert (Flyway-Checksumme). SQLite kann eine CHECK-Constraint nicht per ALTER aendern
-- -> Tabellen-Neuaufbau (siehe Hinweis in V1). generated_file ist Elterntabelle von mail_batch
-- (FK RESTRICT auf generated_file_id), daher muss der Neuaufbau mit PRAGMA foreign_keys = OFF ausserhalb einer
-- Transaktion laufen (executeInTransaction=false in der zugehoerigen .conf, NICHT atomar) und mit
-- PRAGMA foreign_key_check abschliessen. Zum Schluss wird foreign_keys wieder auf ON gesetzt, damit die
-- gepoolte Verbindung den Zustand nicht OFF behaelt.
--
-- Spalten, Typen, Laengen, Primaerschluessel und der eindeutige Index bleiben EXAKT wie in V5 - nur die Menge
-- der erlaubten file_type-Werte wird erweitert. Dadurch bleiben EntitySchemaAlignmentTest/SchemaMetadataTest
-- (Spalten/Index/Laengen) und Hibernate validate unveraendert gruen; nur die Enum-CHECK-Ausrichtung waechst mit.

PRAGMA foreign_keys = OFF;

CREATE TABLE generated_file__new (
    id                integer,
    display_name      varchar(255)  NOT NULL,
    stored_filename   varchar(255)  NOT NULL,
    download_filename varchar(255)  NOT NULL,
    file_type         varchar(32)   NOT NULL CHECK (file_type IN ('DOCX', 'XML', 'PDF', 'XLSX', 'PPTX', 'TXT', 'CSV')),
    content_type      varchar(255)  NOT NULL,
    file_size         bigint        NOT NULL,
    created_at        timestamp     NOT NULL,
    PRIMARY KEY (id)
);

INSERT INTO generated_file__new
    (id, display_name, stored_filename, download_filename, file_type, content_type, file_size, created_at)
SELECT id, display_name, stored_filename, download_filename, file_type, content_type, file_size, created_at
FROM generated_file;

DROP TABLE generated_file;

ALTER TABLE generated_file__new RENAME TO generated_file;

-- Eindeutiger Index mit exakt demselben Namen wie in V5 (Hibernate unique_key_validation=NAMED).
CREATE UNIQUE INDEX uk_generated_file_stored_filename ON generated_file (stored_filename);

-- Referenzielle Integritaet nach dem Neuaufbau pruefen (mail_batch -> generated_file). Leeres Ergebnis = ok.
PRAGMA foreign_key_check;

PRAGMA foreign_keys = ON;
