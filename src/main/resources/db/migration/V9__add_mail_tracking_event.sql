-- Flyway V9: Awareness-Ereignisse (Klicks) fuer den globalen Mail-Composer, bezogen auf mail_delivery.
--
-- Additive Migration; V1-V8 bleiben unveraendert. Bewusst eine eigene Tabelle statt einer Umstellung von
-- tracking_event: tracking_event ist an campaign_recipient gekoppelt (recipient_id NOT NULL); eine
-- Nullbar-Umstellung erforderte in SQLite einen Tabellen-Neuaufbau mit PRAGMA foreign_keys OFF/ON ausserhalb
-- einer Transaktion (siehe Hinweis in V1) - unnoetig riskant. Das Composer-Tracking haengt daher sauber an
-- mail_delivery, waehrend das Kampagnen-Tracking voellig unveraendert bleibt. Der Ereignistyp (Enum
-- TrackingEventType) wird wiederverwendet; die CHECK-Constraint listet exakt dessen Konstanten.
--
-- Mehrere LINK_CLICK-Ereignisse je Zustellung sind erlaubt (Mehrfachklick zaehlt mehrfach): KEIN Unique-Index
-- auf delivery_id oder event_type. Es werden bewusst KEINE IP-/User-Agent-/Fingerprint-/Geodaten gespeichert.
--
-- Foreign-Key-Verhalten wie im Bestand: ON DELETE/UPDATE RESTRICT (die Tracking-Historie verschwindet nie
-- automatisch). Typen entsprechen dem Hibernate-Mapping MailTrackingEvent (integer-Id/FK, varchar(32),
-- timestamp). Ausrichtung sichert EntitySchemaAlignmentTest.

CREATE TABLE mail_tracking_event (
    id          integer,
    delivery_id integer     NOT NULL,
    event_type  varchar(32) NOT NULL CHECK (event_type IN ('LINK_CLICK')),
    occurred_at timestamp   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_mail_tracking_event_delivery FOREIGN KEY (delivery_id)
        REFERENCES mail_delivery (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX ix_mail_tracking_event_delivery ON mail_tracking_event (delivery_id);
