-- Flyway V7: Versandhistorie des globalen E-Mail-Composers (Versandvorgang + Zustellung je Kontakt).
--
-- Additive Migration; V1-V6 bleiben unveraendert. mail_batch haelt einen Versandvorgang (Betreff/Text/
-- optionaler Anhang/Absender/Empfaengeranzahl), mail_delivery je Kontakt die konkrete Zustellung
-- (Status/Versuche/Sendezeitpunkt/Fehlerkategorie). Es werden bewusst KEINE SMTP-Zugangsdaten, KEINE
-- Tracking-Tokens und KEINE vollstaendigen SMTP-Serverantworten gespeichert.
--
-- Foreign-Key-Verhalten wie im Bestand: ON DELETE/UPDATE RESTRICT (kein Cascading), sofortige Pruefung.
-- Eine verwendete Datei bzw. ein verwendeter Kontakt kann nicht geloescht werden, solange die Historie
-- darauf verweist. generated_file_id ist NULLABLE (Versand ohne Anhang).
--
-- Typen/Laengen entsprechen den Hibernate-Mappings MailBatch/MailDelivery (integer-Ids und FK-Spalten,
-- varchar(n) gemaess @Column(length), timestamp fuer Instant). status traegt eine CHECK-Constraint mit
-- exakt allen DeliveryStatus-Konstanten (NOT_SENT/SENT/FAILED) - ein neuer Wert erfordert eine neue Migration.

CREATE TABLE mail_batch (
    id                  integer,
    subject             varchar(255)   NOT NULL,
    body                varchar(10000) NOT NULL,
    sender_email        varchar(255)   NOT NULL,
    sender_name         varchar(255),
    generated_file_id   integer,
    attachment_filename varchar(255),
    recipient_count     integer        NOT NULL,
    created_at          timestamp      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_batch_generated_file FOREIGN KEY (generated_file_id)
        REFERENCES generated_file (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE TABLE mail_delivery (
    id               integer,
    batch_id         integer     NOT NULL,
    contact_id       integer     NOT NULL,
    status           varchar(32) NOT NULL CHECK (status IN ('NOT_SENT', 'SENT', 'FAILED')),
    attempt_count    integer     NOT NULL,
    sent_at          timestamp,
    failure_category varchar(64),
    created_at       timestamp   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_delivery_batch FOREIGN KEY (batch_id)
        REFERENCES mail_batch (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_delivery_contact FOREIGN KEY (contact_id)
        REFERENCES contact (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

-- Indizes auf die Foreign-Key-Spalten: Lookups je Datei/Batch/Kontakt und die FK-Pruefung beim Loeschen
-- eines Elterndatensatzes (SQLite sucht die Kindzeilen ueber diese Spalten).
CREATE INDEX ix_batch_generated_file ON mail_batch (generated_file_id);
CREATE INDEX ix_delivery_batch ON mail_delivery (batch_id);
CREATE INDEX ix_delivery_contact ON mail_delivery (contact_id);
