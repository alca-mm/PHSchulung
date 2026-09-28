-- Flyway V8: Empfaengerbezogene Tracking-Identitaet je Zustellung (mail_delivery.tracking_token_hash).
--
-- Additive Migration; V1-V7 bleiben unveraendert (Flyway-Checksumme). Gespeichert wird ausschliesslich der
-- SHA-256-Hash (Hex) eines kryptografisch starken Zufallstokens - NIE der Klartext-Token. Ein spaeter
-- eingehender Token wird gehasht und ueber diesen Hash nachgeschlagen (oeffentlicher Endpoint GET /t/{token}).
--
-- Nullbarkeit (migrationssicher): Die Spalte ist bewusst NULLBAR. Etwaige vorhandene MailDelivery-Zeilen
-- (z. B. aus Entwicklungstests, vor Einfuehrung des Trackings) bleiben damit gueltig, ohne dass Daten
-- geloescht oder mit Pseudo-Werten befuellt werden muessten. Neue Deliveries erhalten anwendungsseitig immer
-- einen Hash (siehe MailComposerService); die fachliche NOT-NULL-Zusage gilt also fuer neue Datensaetze.
--
-- Eindeutigkeit: Unique-Index auf tracking_token_hash. In SQLite gelten mehrere NULL-Werte als verschieden,
-- daher ist der Unique-Index mit den (moeglichen) NULLs von Alt-Zeilen vertraeglich. Der Name muss zum
-- @Index(unique=true) auf MailDelivery passen (Hibernate unique_key_validation=NAMED). Laenge 64 = Hex-SHA-256.

ALTER TABLE mail_delivery ADD COLUMN tracking_token_hash varchar(64);

CREATE UNIQUE INDEX uk_mail_delivery_tracking_token_hash ON mail_delivery (tracking_token_hash);
