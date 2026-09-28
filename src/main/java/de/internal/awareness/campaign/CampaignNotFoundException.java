package de.internal.awareness.campaign;

/**
 * Fachlicher Fehler: Es wurde eine Kampagne ueber eine ID angefragt, die nicht existiert.
 * Bewusst schlank (eine einzelne RuntimeException statt einer Exception-Hierarchie). Die Weboberfläche
 * uebersetzt dies in eine kontrollierte 404-Antwort (siehe {@code web}-Paket).
 */
public class CampaignNotFoundException extends RuntimeException {

    private final Long campaignId;

    public CampaignNotFoundException(Long campaignId) {
        super("Kampagne nicht gefunden: " + campaignId);
        this.campaignId = campaignId;
    }

    public Long getCampaignId() {
        return campaignId;
    }
}
