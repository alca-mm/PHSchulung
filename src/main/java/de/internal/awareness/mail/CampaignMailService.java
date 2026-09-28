package de.internal.awareness.mail;

import de.internal.awareness.campaign.Campaign;
import de.internal.awareness.campaign.CampaignService;
import de.internal.awareness.config.AppMailProperties;
import de.internal.awareness.recipient.CampaignRecipient;
import de.internal.awareness.recipient.CampaignRecipientRepository;
import de.internal.awareness.recipient.DeliveryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Anwendungsdienst fuer den Versand von Trainings-E-Mails einer Kampagne.
 *
 * <p>Sicherheitsgrenzen dieses Dienstes:</p>
 * <ul>
 *   <li><b>Fail-closed:</b> vor jedem Versand werden alle Vorbedingungen geprueft
 *       ({@link #checkReadiness(Long)}); ist eine verletzt, wird NICHTS versendet.</li>
 *   <li><b>Eine Mail pro Empfaenger:</b> jeder Empfaenger erhaelt genau eine eigene Nachricht mit einem
 *       einzigen To-Empfaenger. Es werden nie mehrere Adressen in To/CC/BCC gebuendelt.</li>
 *   <li><b>Datensparsames Logging:</b> bei Fehlern werden nur die Empfaenger-Id und eine kurze,
 *       sanitizte Fehlerkategorie geloggt - nie die Adresse, nie die rohe Ausnahme-Meldung oder eine
 *       SMTP-Serverantwort. Es werden keine Secrets/Tokens geloggt.</li>
 * </ul>
 */
@Service
public class CampaignMailService {

    private static final Logger log = LoggerFactory.getLogger(CampaignMailService.class);

    private final CampaignService campaignService;
    private final CampaignRecipientRepository recipientRepository;
    private final AppMailProperties appMailProperties;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String mailHost;
    private final TransactionTemplate txTemplate;

    public CampaignMailService(CampaignService campaignService,
                               CampaignRecipientRepository recipientRepository,
                               AppMailProperties appMailProperties,
                               ObjectProvider<JavaMailSender> mailSenderProvider,
                               PlatformTransactionManager transactionManager,
                               @Value("${spring.mail.host:}") String mailHost) {
        this.campaignService = campaignService;
        this.recipientRepository = recipientRepository;
        this.appMailProperties = appMailProperties;
        this.mailSenderProvider = mailSenderProvider;
        this.mailHost = mailHost;
        // Standard-Propagation REQUIRED: in Produktion (kein umgebender Tx) committet jeder Empfaenger
        // einzeln (Dauerhaftigkeit von Teilergebnissen); in Tests mit umgebender @Transactional-Tx nimmt
        // das Template daran teil (Rollback/Isolation bleiben erhalten).
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Prueft (nur lesend), ob fuer die Kampagne echt versendet werden darf. Sammelt alle verletzten
     * Vorbedingungen als menschenlesbare deutsche Meldungen. {@code ready} ist genau dann {@code true},
     * wenn keine Vorbedingung verletzt ist.
     *
     * @param campaignId Id der Kampagne (wirft {@code CampaignNotFoundException}, wenn unbekannt)
     * @return das Pruefergebnis inklusive Gesamt- und versendbarer Empfaengerzahl
     */
    @Transactional(readOnly = true)
    public SendReadiness checkReadiness(Long campaignId) {
        Campaign campaign = campaignService.getById(campaignId);

        long total = recipientRepository.countByCampaign(campaign);
        long notSent = recipientRepository.countByCampaignAndDeliveryStatus(campaign, DeliveryStatus.NOT_SENT);
        long failed = recipientRepository.countByCampaignAndDeliveryStatus(campaign, DeliveryStatus.FAILED);
        long sendable = notSent + failed;

        List<String> blockers = new ArrayList<>();

        if (!appMailProperties.isLiveSendEnabled()) {
            blockers.add("Testmodus: echter Versand ist deaktiviert (APP_MAIL_LIVE_SEND_ENABLED=false).");
        }
        if (!StringUtils.hasText(mailHost) || mailSenderProvider.getIfAvailable() == null) {
            blockers.add("SMTP ist nicht konfiguriert (MAIL_HOST fehlt).");
        }
        if (total == 0) {
            blockers.add("Kein Empfaenger vorhanden.");
        }
        if (!StringUtils.hasText(campaign.getEmailSubject())) {
            blockers.add("Betreff fehlt.");
        }
        if (!StringUtils.hasText(campaign.getEmailBody())) {
            blockers.add("E-Mail-Text fehlt.");
        }
        String sender = campaign.getSenderEmail();
        if (!StringUtils.hasText(sender)) {
            blockers.add("Absender fehlt.");
        } else if (!appMailProperties.isSenderAllowed(sender)) {
            blockers.add("Absender ist nicht in der Allowlist erlaubt.");
        }
        // Bewusst KEIN Blocker fuer "alles bereits versendet": die Konfiguration ist dann versandbereit
        // (>=1 Empfaenger, gemaess Spezifikation), und ein erneuter Versand ist idempotent - bereits
        // versendete Empfaenger werden in sendToAll uebersprungen (siehe skippedAlreadySent). Die
        // Aufteilung "offen/gesendet/fehlgeschlagen" zeigt die Oberflaeche separat an.

        boolean ready = blockers.isEmpty();
        return new SendReadiness(ready, blockers, total, sendable);
    }

    /**
     * Versendet die Trainings-Mail an alle (noch) versendbaren Empfaenger der Kampagne - je Empfaenger
     * genau eine Einzelmail. Vor dem Versand wird {@link #checkReadiness(Long)} ausgewertet; ist die
     * Kampagne nicht bereit, wird eine {@link SendNotAllowedException} geworfen und NICHTS versendet.
     *
     * <p>Verhalten je Empfaenger:</p>
     * <ul>
     *   <li>bereits {@link DeliveryStatus#SENT}: uebersprungen (nie erneuter Versand);</li>
     *   <li>Domain nicht in der Empfaenger-Allowlist: blockiert, bleibt {@code NOT_SENT};</li>
     *   <li>sonst: Versuch registrieren, Einzelmail senden, Erfolg/Fehler festhalten.</li>
     * </ul>
     * Ein einzelner fehlgeschlagener Empfaenger stoppt die uebrigen nicht.
     *
     * @param campaignId Id der Kampagne
     * @return Zusammenfassung des Versandlaufs
     * @throws SendNotAllowedException wenn eine Vorbedingung verletzt ist (es wird nichts versendet)
     */
    public SendSummary sendToAll(Long campaignId) {
        SendReadiness readiness = checkReadiness(campaignId);
        if (!readiness.ready()) {
            // Fail-closed: keinerlei Versand, wenn eine Vorbedingung verletzt ist.
            throw new SendNotAllowedException(readiness.blockers());
        }

        Campaign campaign = campaignService.getById(campaignId);
        // Ab hier ist per checkReadiness garantiert vorhanden: erlaubter Absender, Betreff, Text und
        // ein verfuegbarer JavaMailSender.
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        String from = buildFrom(campaign);
        String subject = campaign.getEmailSubject();
        String body = campaign.getEmailBody();

        int attempted = 0;
        int sent = 0;
        int failed = 0;
        int skippedAlreadySent = 0;
        int blockedByRecipientAllowlist = 0;
        List<String> failedEmails = new ArrayList<>();
        List<String> blockedEmails = new ArrayList<>();

        for (CampaignRecipient recipient : recipientRepository.findByCampaign(campaign)) {
            if (recipient.getDeliveryStatus() == DeliveryStatus.SENT) {
                // Nie erneut an bereits erfolgreich versendete Empfaenger senden.
                skippedAlreadySent++;
                continue;
            }
            if (!appMailProperties.isRecipientDomainAllowed(recipient.getEmail())) {
                // Domain nicht erlaubt: nicht versenden, Status bleibt NOT_SENT.
                blockedByRecipientAllowlist++;
                blockedEmails.add(recipient.getEmail());
                continue;
            }

            Long recipientId = recipient.getId();
            String email = recipient.getEmail();
            attempted++;
            // Jeden Empfaenger in EIGENER Transaktion verarbeiten und committen: ein spaeterer Fehler
            // (z. B. beim Flush eines weiteren Empfaengers oder ein Error) darf bereits erfolgte
            // Zustellungen NICHT zurueckrollen - sonst droht erneuter Versand an bereits belieferte
            // Empfaenger. Ein regulaerer Sendefehler wird in sendOne abgefangen (Empfaenger -> FAILED,
            // Transaktion committet), damit der Batch weiterlaeuft.
            boolean ok = Boolean.TRUE.equals(txTemplate.execute(
                    status -> sendOne(recipientId, from, subject, body, email, mailSender)));
            if (ok) {
                sent++;
            } else {
                failed++;
                failedEmails.add(email);
            }
        }

        return new SendSummary(attempted, sent, failed, skippedAlreadySent,
                blockedByRecipientAllowlist, failedEmails, blockedEmails);
    }

    /**
     * Verarbeitet genau einen Empfaenger innerhalb der aktuellen (pro-Empfaenger-)Transaktion: laedt ihn
     * frisch, registriert den Versuch, sendet die Einzelmail (genau EIN To, nie CC/BCC) und haelt
     * Erfolg/Fehler fest. Ein regulaerer Sendefehler wird hier abgefangen: der Empfaenger wird FAILED mit
     * einer kurzen, sanitizten Kategorie, und die Transaktion committet trotzdem (der Batch laeuft weiter).
     * Es werden nur Empfaenger-Id und Kategorie geloggt - nie Adresse, Ausnahme-Meldung oder Serverantwort.
     *
     * @return {@code true} bei erfolgreichem Versand, sonst {@code false}
     */
    private boolean sendOne(Long recipientId, String from, String subject, String body, String email,
                            JavaMailSender mailSender) {
        CampaignRecipient recipient = recipientRepository.findById(recipientId).orElseThrow();
        recipient.recordAttempt(Instant.now());
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(email);
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);

            recipient.recordSent(Instant.now());
            recipientRepository.saveAndFlush(recipient);
            return true;
        } catch (Exception ex) {
            String category = MailFailureCategory.of(ex);
            recipient.recordFailure(category);
            recipientRepository.saveAndFlush(recipient);
            log.warn("Versand fehlgeschlagen fuer Empfaenger id={}, Kategorie={}", recipientId, category);
            return false;
        }
    }

    /**
     * Baut den Absender-Header: mit Anzeigename {@code "Name <adresse>"}, sonst nur die Adresse. Der
     * Anzeigename wird defensiv von CR/LF befreit (Schutz gegen Header-Injektion; JavaMail/InternetAddress
     * wuerde solche Zeichen ohnehin ablehnen). Es werden ausschliesslich die (nicht sensiblen)
     * Kampagnen-Absenderdaten verwendet.
     */
    private static String buildFrom(Campaign campaign) {
        String email = campaign.getSenderEmail();
        String name = campaign.getSenderName();
        if (StringUtils.hasText(name)) {
            String sanitizedName = name.replaceAll("[\\r\\n]", " ").trim();
            return String.format("%s <%s>", sanitizedName, email);
        }
        return email;
    }
}
