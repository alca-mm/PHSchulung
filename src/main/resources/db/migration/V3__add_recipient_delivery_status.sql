-- Flyway V3: Versandstatus je Empfaenger (statt separater MailDelivery-Entity).
--
-- Additive Migration; V1/V2 bleiben unveraendert. Der Versandstatus wird bewusst als Spalten auf
-- campaign_recipient modelliert (nicht als neue Entity): das ist die einfachere, langfristig saubere
-- Variante und haelt die Entity-Menge stabil (siehe EntitySchemaAlignmentTest / FlywayMigrationTest).
--
-- delivery_status traegt eine CHECK-Constraint mit exakt allen Enum-Konstanten (NOT_SENT/SENT/FAILED);
-- neue Enum-Werte erfordern eine neue Migration (Tabellen-Neuaufbau). SQLite erlaubt CHECK und einen
-- Nicht-NULL-Default beim ADD COLUMN. failure_category speichert nur eine kurze, sanitizte Kategorie -
-- NIEMALS vollstaendige SMTP-Serverantworten oder sensible Fehlerdetails.

ALTER TABLE campaign_recipient ADD COLUMN delivery_status varchar(32) NOT NULL DEFAULT 'NOT_SENT'
    CHECK (delivery_status IN ('NOT_SENT', 'SENT', 'FAILED'));
ALTER TABLE campaign_recipient ADD COLUMN attempt_count integer NOT NULL DEFAULT 0;
ALTER TABLE campaign_recipient ADD COLUMN last_attempt_at timestamp;
ALTER TABLE campaign_recipient ADD COLUMN sent_at timestamp;
ALTER TABLE campaign_recipient ADD COLUMN failure_category varchar(64);
