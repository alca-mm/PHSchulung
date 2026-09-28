-- Flyway V6: Globale, kampagnenunabhaengige Empfaengerliste (Kontakte).
--
-- Additive Migration; V1-V5 bleiben unveraendert. Ein Contact gehoert - anders als campaign_recipient -
-- KEINER Kampagne, sondern der globalen, wiederverwendbaren Liste. Die CampaignRecipient-Semantik bleibt
-- vollstaendig erhalten (eigene Tabelle, unveraendert).
--
-- Typen/Laengen entsprechen dem Hibernate-Mapping Contact: integer-IDENTITY-Id (rowid-Alias),
-- varchar(255) fuer email/display_name, timestamp fuer created_at (siehe EntitySchemaAlignmentTest).
--
-- Eindeutigkeit der E-Mail-Adresse GLOBAL und CASE-INSENSITIVE ueber COLLATE NOCASE: 'Max@Example.invalid'
-- und 'max@example.invalid' gelten als dieselbe Adresse. pragma_index_info meldet die Indexspalte weiterhin
-- als 'email', daher passt der benannte Unique-Key zum @Index(unique=true) auf Contact
-- (Hibernate unique_key_validation=NAMED). Die anwendungsseitige Duplikatpruefung
-- (existsByEmailIgnoreCase) liefert eine verstaendliche Meldung; dieser Index bleibt der DB-seitige Schutz.

CREATE TABLE contact (
    id           integer,
    email        varchar(255) NOT NULL,
    display_name varchar(255),
    created_at   timestamp    NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uk_contact_email ON contact (email COLLATE NOCASE);
