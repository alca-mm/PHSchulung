package de.internal.awareness.campaign;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Kleiner Anwendungsdienst fuer Kampagnen: laden und anlegen. Bewusst ohne Status-Workflow
 * (kein Starten/Versenden, kein Statuswechsel) - das ist nicht Teil dieses Schritts.
 *
 * <p>Transaktionen gezielt: Lesezugriffe {@code readOnly}, das Anlegen schreibend.</p>
 */
@Service
@Transactional(readOnly = true)
public class CampaignService {

    private final CampaignRepository campaignRepository;

    public CampaignService(CampaignRepository campaignRepository) {
        this.campaignRepository = campaignRepository;
    }

    /**
     * Alle Kampagnen, neueste zuerst (fuer die Uebersicht). Sekundaersortierung nach {@code id} DESC als
     * stabiler Tiebreaker: werden zwei Kampagnen im selben Zeittakt angelegt (identisches {@code createdAt}),
     * bleibt die Reihenfolge deterministisch (die hoehere IDENTITY-Id ist die spaeter angelegte Kampagne).
     */
    public List<Campaign> findAll() {
        return campaignRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    /**
     * Laedt eine Kampagne oder wirft {@link CampaignNotFoundException}, wenn sie nicht existiert.
     * Grundlage fuer eine kontrollierte 404-Behandlung in der Weboberflaeche.
     */
    public Campaign getById(Long id) {
        return campaignRepository.findById(id)
                .orElseThrow(() -> new CampaignNotFoundException(id));
    }

    /**
     * Legt eine neue Kampagne im Status {@code DRAFT} an. Zeitstempel setzt die Entity selbst
     * (via {@code @PrePersist}). Ungueltige Eingaben (leerer Name/Betreff) werden durch Bean
     * Validation beim Flush abgelehnt.
     */
    @Transactional
    public Campaign create(String name, String description, String emailSubject) {
        return create(name, description, null, null, emailSubject, null);
    }

    /**
     * Legt eine neue Kampagne im Status {@code DRAFT} inklusive Absender und E-Mail-Text an. Optionale
     * Leerwerte (Beschreibung, Absendername) werden zu {@code null} normalisiert. Die fachliche Pflicht
     * (gueltiger Absender, nicht-leerer Betreff/Text) wird am Formular per Bean Validation geprueft; hier
     * werden die Werte lediglich uebernommen. Ungueltige Kernfelder (leerer Name/Betreff) lehnt Bean
     * Validation beim Flush ab.
     */
    @Transactional
    public Campaign create(String name, String description, String senderName, String senderEmail,
                           String emailSubject, String emailBody) {
        Campaign campaign = new Campaign(name, emailSubject);
        if (description != null && !description.isBlank()) {
            campaign.setDescription(description);
        }
        if (senderName != null && !senderName.isBlank()) {
            campaign.setSenderName(senderName.trim());
        }
        if (senderEmail != null && !senderEmail.isBlank()) {
            campaign.setSenderEmail(senderEmail.trim());
        }
        if (emailBody != null && !emailBody.isBlank()) {
            campaign.setEmailBody(emailBody);
        }
        // Status bleibt der Default DRAFT aus dem Entity-Konstruktor.
        return campaignRepository.saveAndFlush(campaign);
    }
}
