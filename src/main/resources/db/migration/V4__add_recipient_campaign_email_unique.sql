-- Flyway V4: Eindeutigkeit einer E-Mail-Adresse INNERHALB einer Kampagne auf DB-Ebene.
--
-- Additive Migration; bestehende Migrationen bleiben unveraendert. Der Unique-Index verhindert
-- unbeabsichtigte Duplikate innerhalb derselben Kampagne (campaign_id + email), erlaubt aber bewusst
-- dieselbe Adresse in VERSCHIEDENEN Kampagnen (kein globaler Unique auf email allein).
--
-- Der Name muss zum @Index(unique=true) auf CampaignRecipient passen (Hibernate validiert benannte
-- Unique-Keys: unique_key_validation=NAMED). Die anwendungsseitige Duplikatpruefung liefert eine
-- verstaendliche Meldung; dieser Index bleibt der endgueltige Schutz auf DB-Ebene.

CREATE UNIQUE INDEX uk_recipient_campaign_email ON campaign_recipient (campaign_id, email);
