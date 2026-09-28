-- Flyway V1: Initiales Schema fuer den phishing-awareness-trainer.
--
-- Flyway ist die EINZIGE Quelle der Schema-Erzeugung/-Aenderung. Hibernate laeuft mit
-- ddl-auto=validate und veraendert das Schema nicht. Spaltennamen, Typen, Laengen, NOT NULL und die
-- CHECK-Constraints der Enum-Spalten entsprechen dem DDL, das Hibernate (Community-SQLiteDialect)
-- fuer die Entities erwarten wuerde: integer fuer die IDENTITY-Ids und die FK-Spalten
-- (@JdbcTypeCode(INTEGER)), varchar(n) gemaess @Column(length), timestamp fuer Instant.
-- "id integer" + PRIMARY KEY (id) macht die Spalte zum rowid-Alias (IDENTITY-Generierung).
-- Ergaenzt um das, was der Dialekt nicht selbst emittiert: echte Foreign Keys sowie Unique-/Index-DDL.
--
-- Foreign-Key-Verhalten: ON DELETE RESTRICT / ON UPDATE RESTRICT (kein Cascading Delete).
-- Kampagnen mit Empfaengern bzw. Empfaenger mit TrackingEvents koennen nicht geloescht werden;
-- die Tracking-Historie verschwindet nie automatisch. RESTRICT prueft sofort (auch bei aufgeschobenen
-- Constraints, DEFERRABLE / PRAGMA defer_foreign_keys), anders als NO ACTION. Durchsetzung zur
-- Laufzeit ueber PRAGMA foreign_keys = ON auf jeder Pool-Verbindung
-- (spring.datasource.hikari.connection-init-sql).
--
-- Hinweise fuer kuenftige Migrationen (SQLite):
-- * Bereits angewendete Migrationen nie mehr aendern (Flyway-Checksumme) - immer neue V<n>__*.sql.
-- * Eine Migration darf PRAGMA foreign_keys NIE im Zustand OFF hinterlassen: Flyway nutzt die
--   Pool-Verbindungen der Anwendung, der Zustand bliebe auf der Verbindung bestehen.
-- * Tabellen-Neuaufbau (z. B. neuer Enum-Wert im CHECK) erfordert PRAGMA foreign_keys OFF/ON
--   ausserhalb einer Transaktion. Flyway stuft dieses PRAGMA als nicht-transaktional ein und lehnt
--   es gemischt mit DDL ab (mixed=false): eigenes Skript bzw. executeInTransaction=false verwenden
--   (nicht atomar!) und mit PRAGMA foreign_key_check abschliessen.

CREATE TABLE campaign (
    id            integer,
    name          varchar(255) NOT NULL,
    description   varchar(2000),
    email_subject varchar(255) NOT NULL,
    status        varchar(32)  NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'COMPLETED')),
    created_at    timestamp    NOT NULL,
    updated_at    timestamp    NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE campaign_recipient (
    id                  integer,
    campaign_id         integer      NOT NULL,
    email               varchar(255) NOT NULL,
    display_name        varchar(255),
    tracking_token_hash varchar(64)  NOT NULL,
    created_at          timestamp    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_recipient_campaign FOREIGN KEY (campaign_id)
        REFERENCES campaign (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE TABLE tracking_event (
    id           integer,
    recipient_id integer     NOT NULL,
    event_type   varchar(32) NOT NULL CHECK (event_type IN ('LINK_CLICK')),
    occurred_at  timestamp   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_event_recipient FOREIGN KEY (recipient_id)
        REFERENCES campaign_recipient (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

-- Eindeutigkeit der Tracking-Identitaet (SHA-256-Hash) auf DB-Ebene. Der Name muss zum @Index in
-- CampaignRecipient passen (Hibernate validiert ihn: unique_key_validation=NAMED).
CREATE UNIQUE INDEX uk_recipient_tracking_token_hash ON campaign_recipient (tracking_token_hash);

-- Indizes auf die Foreign-Key-Spalten: Lookups je Kampagne / je Empfaenger und die FK-Pruefung
-- beim Loeschen eines Elterndatensatzes (SQLite sucht dann die Kindzeilen ueber diese Spalten).
CREATE INDEX ix_recipient_campaign_id ON campaign_recipient (campaign_id);
CREATE INDEX ix_event_recipient_id ON tracking_event (recipient_id);
