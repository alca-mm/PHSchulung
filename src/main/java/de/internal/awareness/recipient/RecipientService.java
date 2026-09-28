package de.internal.awareness.recipient;

import de.internal.awareness.campaign.Campaign;
import de.internal.awareness.campaign.CampaignNotFoundException;
import de.internal.awareness.campaign.CampaignRepository;
import de.internal.awareness.tracking.TrackingTokenFactory;
import de.internal.awareness.tracking.TrackingTokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Kleiner Anwendungsdienst fuer Empfaenger: Empfaenger einer Kampagne laden, einen einzelnen Empfaenger
 * anlegen, mehrere Empfaenger per Freitext importieren und die Statusaufschluesselung einer Kampagne
 * ermitteln. Beim Anlegen wird eine sichere Tracking-Identitaet erzeugt; persistiert wird ausschliesslich
 * deren SHA-256-Hash. Der Klartext-Token wird NICHT gespeichert und NICHT geloggt; er wird lediglich
 * einmalig ueber {@link RecipientRegistration} zurueckgegeben.
 */
@Service
@Transactional(readOnly = true)
public class RecipientService {

    /**
     * Begrenzte Anzahl Versuche zur Erzeugung einer eindeutigen Tracking-Identitaet. Eine Kollision
     * ist bei 256 Bit Entropie praktisch unmoeglich; die Grenze verhindert dennoch zuverlaessig eine
     * Endlosschleife. Der Unique-Index auf {@code tracking_token_hash} bleibt der endgueltige Schutz.
     */
    static final int MAX_TOKEN_ATTEMPTS = 5;

    private final CampaignRepository campaignRepository;
    private final CampaignRecipientRepository recipientRepository;
    private final TrackingTokenFactory tokenFactory;
    private final BulkRecipientParser bulkParser;

    public RecipientService(CampaignRepository campaignRepository,
                            CampaignRecipientRepository recipientRepository,
                            TrackingTokenFactory tokenFactory,
                            BulkRecipientParser bulkParser) {
        this.campaignRepository = campaignRepository;
        this.recipientRepository = recipientRepository;
        this.tokenFactory = tokenFactory;
        this.bulkParser = bulkParser;
    }

    /**
     * Alle Empfaenger einer bestehenden Kampagne. Wirft {@link CampaignNotFoundException}, wenn die
     * Kampagne nicht existiert.
     */
    public List<CampaignRecipient> findByCampaign(Long campaignId) {
        Campaign campaign = requireCampaign(campaignId);
        return recipientRepository.findByCampaign(campaign);
    }

    /**
     * Legt einen neuen Empfaenger in einer bestehenden Kampagne an.
     *
     * <p>Ablauf: Kampagne pruefen -&gt; Duplikat-Vorpruefung (dieselbe Adresse ist je Kampagne nur einmal
     * erlaubt) -&gt; eindeutige Tracking-Identitaet erzeugen (nur der Hash wird gespeichert) -&gt;
     * Empfaenger speichern. Ist die Adresse in dieser Kampagne bereits vorhanden, wird eine
     * {@link DuplicateRecipientException} geworfen. Ungueltige Eingaben (leere/ungueltige E-Mail) werden
     * bewusst NICHT hier abgefangen, sondern fallen wie bisher durch Bean Validation beim Flush. Die
     * Rueckgabe enthaelt einmalig den Klartext-Token.</p>
     */
    @Transactional
    public RecipientRegistration addRecipient(Long campaignId, String email, String displayName) {
        Campaign campaign = requireCampaign(campaignId);
        if (recipientRepository.existsByCampaignAndEmailIgnoreCase(campaign, email)) {
            throw new DuplicateRecipientException(email);
        }
        return registerNewRecipient(campaign, email, displayName);
    }

    /**
     * Importiert mehrere Empfaenger aus Freitext (eine Zeile je Empfaenger, siehe
     * {@link BulkRecipientParser}). Ungueltige Zeilen werden gemeldet, nicht angelegt. Duplikate werden
     * case-insensitive uebersprungen - sowohl gegen bereits vorhandene Empfaenger der Kampagne als auch
     * innerhalb desselben Imports (die erste Nennung gewinnt).
     *
     * <p>Da die Adressen zuvor validiert und dedupliziert wurden, ist beim Speichern der verbleibenden,
     * eindeutigen Empfaenger kein Flush-Fehler und keine Verletzung des Unique-Index zu erwarten.</p>
     */
    @Transactional
    public BulkImportResult importRecipients(Long campaignId, String rawText) {
        Campaign campaign = requireCampaign(campaignId);
        BulkRecipientParser.ParsedBulk parsed = bulkParser.parse(rawText);

        int added = 0;
        int duplicates = 0;
        Set<String> seenEmails = new HashSet<>();

        for (BulkRecipientParser.ParsedRecipient candidate : parsed.valid()) {
            String normalized = candidate.email().toLowerCase(Locale.ROOT);
            boolean newInBatch = seenEmails.add(normalized);
            if (!newInBatch || recipientRepository.existsByCampaignAndEmailIgnoreCase(campaign, candidate.email())) {
                duplicates++;
                continue;
            }
            persistNewRecipient(campaign, candidate.email(), candidate.displayName());
            added++;
        }
        return new BulkImportResult(added, duplicates, parsed.invalidLines());
    }

    /**
     * Statusaufschluesselung einer bestehenden Kampagne: Gesamtzahl der Empfaenger sowie Aufteilung nach
     * {@link DeliveryStatus}. Wirft {@link CampaignNotFoundException}, wenn die Kampagne nicht existiert.
     */
    public RecipientStats stats(Long campaignId) {
        Campaign campaign = requireCampaign(campaignId);
        long total = recipientRepository.countByCampaign(campaign);
        long sent = recipientRepository.countByCampaignAndDeliveryStatus(campaign, DeliveryStatus.SENT);
        long failed = recipientRepository.countByCampaignAndDeliveryStatus(campaign, DeliveryStatus.FAILED);
        long notSent = recipientRepository.countByCampaignAndDeliveryStatus(campaign, DeliveryStatus.NOT_SENT);
        return new RecipientStats(total, notSent, sent, failed);
    }

    private Campaign requireCampaign(Long campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));
    }

    /**
     * Gemeinsamer Kern der Empfaenger-Anlage: erzeugt eine eindeutige Tracking-Identitaet (nur der Hash
     * wird gespeichert), baut den Empfaenger und speichert ihn (Flush). Gibt zusaetzlich den EINMALIGEN
     * Klartext-Token zurueck. Von {@link #addRecipient} genutzt, um den Token an den Aufrufer
     * weiterzureichen.
     */
    private RecipientRegistration registerNewRecipient(Campaign campaign, String email, String displayName) {
        TrackingTokens.GeneratedToken token = generateUniqueToken();
        CampaignRecipient recipient = new CampaignRecipient(campaign, email, token.tokenHash());
        if (displayName != null && !displayName.isBlank()) {
            recipient.setDisplayName(displayName);
        }
        CampaignRecipient saved = recipientRepository.saveAndFlush(recipient);
        return new RecipientRegistration(saved, token.token());
    }

    /**
     * Legt einen neuen Empfaenger an (Token-Erzeugung ueber {@link #generateUniqueToken()}, bauen und
     * {@code saveAndFlush}) und gibt den gespeicherten Empfaenger zurueck. Fuer Aufrufer ohne Bedarf am
     * Klartext-Token (Massenimport). Behavior identisch zu {@link #addRecipient} abzueglich der
     * einmaligen Token-Rueckgabe.
     */
    private CampaignRecipient persistNewRecipient(Campaign campaign, String email, String displayName) {
        return registerNewRecipient(campaign, email, displayName).recipient();
    }

    /**
     * Erzeugt eine Tracking-Identitaet, deren Hash noch nicht vergeben ist. Bei (praktisch
     * unmoeglicher) Kollision wird ein neues Token erzeugt, hoechstens {@link #MAX_TOKEN_ATTEMPTS}
     * Mal. Der bereits vergebene Hash wird nie erneut gespeichert.
     */
    private TrackingTokens.GeneratedToken generateUniqueToken() {
        for (int attempt = 1; attempt <= MAX_TOKEN_ATTEMPTS; attempt++) {
            TrackingTokens.GeneratedToken candidate = tokenFactory.newToken();
            if (!recipientRepository.existsByTrackingTokenHash(candidate.tokenHash())) {
                return candidate;
            }
        }
        throw new TrackingTokenCollisionException(MAX_TOKEN_ATTEMPTS);
    }
}
