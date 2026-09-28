-- Flyway V2: Mail-Felder fuer den Versand ergaenzen (Absender + E-Mail-Text).
--
-- Additive Migration: V1 bleibt unveraendert (Flyway-Checksumme). Die Spalten sind bewusst NULLABLE,
-- damit bestehende Kampagnen (ohne Absender/Text) gueltig bleiben; die fachliche Pflicht (gueltiger
-- Absender, nicht-leerer Betreff/Text) wird beim Anlegen ueber Bean Validation am Formular sowie vor
-- dem Versand geprueft, nicht ueber DB-NOT-NULL. Typen/Laengen entsprechen dem, was Hibernate fuer die
-- gemappten String-Felder erzeugen wuerde (varchar(n)); die Ausrichtung sichert EntitySchemaAlignmentTest.
--
-- Die sichtbare Absenderadresse ist KEIN Secret. SMTP-Zugangsdaten werden hier NICHT gespeichert.

ALTER TABLE campaign ADD COLUMN sender_email varchar(255);
ALTER TABLE campaign ADD COLUMN sender_name  varchar(255);
ALTER TABLE campaign ADD COLUMN email_body   varchar(10000);
