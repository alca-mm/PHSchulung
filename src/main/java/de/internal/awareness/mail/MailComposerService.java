package de.internal.awareness.mail;

import de.internal.awareness.config.AppMailProperties;
import de.internal.awareness.config.AppTrackingProperties;
import de.internal.awareness.contact.Contact;
import de.internal.awareness.contact.ContactRepository;
import de.internal.awareness.contact.ContactService;
import de.internal.awareness.file.AttachmentPersonalizer;
import de.internal.awareness.file.GeneratedFile;
import de.internal.awareness.file.GeneratedFileService;
import de.internal.awareness.file.GeneratedFileType;
import de.internal.awareness.recipient.DeliveryStatus;
import de.internal.awareness.tracking.TrackingLinkPolicy;
import de.internal.awareness.tracking.TrackingTokenFactory;
import de.internal.awareness.tracking.TrackingTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import jakarta.mail.internet.MimeMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Anwendungsdienst des globalen E-Mail-Composers: versendet eine Nachricht (Betreff/Text/optionaler
 * Anhang) als individuelle Einzelmails an ausgewaehlte {@link Contact}s und protokolliert den Vorgang
 * ({@link MailBatch}/{@link MailDelivery}).
 *
 * <p>Sicherheitsgrenzen (wiederverwendet aus dem bestehenden Kampagnenversand):</p>
 * <ul>
 *   <li><b>Fail-closed:</b> {@link #checkReadiness()} plus Pflichtfeld-/Auswahlpruefung; ist etwas verletzt,
 *       wird NICHTS versendet und KEIN Batch angelegt.</li>
 *   <li><b>Absender aus Konfiguration:</b> ausschliesslich {@code app.mail.default-sender}; nicht aus dem
 *       Request ueberschreibbar. Muss durch die bestehende Sender-Allowlist erlaubt sein.</li>
 *   <li><b>Empfaenger-Allowlist:</b> nicht erlaubte Domains werden markiert, nicht versendet.</li>
 *   <li><b>Eine Mail pro Empfaenger:</b> genau ein To, nie CC/BCC.</li>
 *   <li><b>Nur interne IDs:</b> Anhang und Empfaenger werden ausschliesslich ueber gespeicherte Datensaetze
 *       aufgeloest; manipulierte/unbekannte IDs werden abgelehnt. Kein Dateipfad aus dem Request.</li>
 *   <li><b>Datensparsames Logging:</b> nur Delivery-Id/Kontakt-Id und eine kurze, sanitizte Fehlerkategorie -
 *       nie Adresse, rohe Ausnahme oder SMTP-Serverantwort; keine Secrets/Tokens.</li>
 * </ul>
 */
@Service
public class MailComposerService {

    private static final Logger log = LoggerFactory.getLogger(MailComposerService.class);

    /** Fehlerkategorie fuer durch die Empfaenger-Allowlist blockierte Empfaenger (kurz, sanitizt). */
    static final String BLOCKED_CATEGORY = "BLOCKED";

    /** Sichtbare Einleitung des Trainingslinks im Plaintext-Koerper. */
    static final String TRAINING_LINK_LABEL = "Dokument / Informationen oeffnen: ";

    /** Begrenzte Versuche zur Erzeugung einer eindeutigen Tracking-Identitaet (Kollision praktisch unmoeglich). */
    static final int MAX_TOKEN_ATTEMPTS = 5;

    private final AppMailProperties appMailProperties;
    private final AppTrackingProperties appTrackingProperties;
    private final ContactService contactService;
    private final ContactRepository contactRepository;
    private final GeneratedFileService generatedFileService;
    private final AttachmentPersonalizer attachmentPersonalizer;
    private final TrackingTokenFactory tokenFactory;
    private final MailBatchRepository mailBatchRepository;
    private final MailDeliveryRepository mailDeliveryRepository;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String mailHost;
    private final TransactionTemplate txTemplate;

    public MailComposerService(AppMailProperties appMailProperties,
                               AppTrackingProperties appTrackingProperties,
                               ContactService contactService,
                               ContactRepository contactRepository,
                               GeneratedFileService generatedFileService,
                               AttachmentPersonalizer attachmentPersonalizer,
                               TrackingTokenFactory tokenFactory,
                               MailBatchRepository mailBatchRepository,
                               MailDeliveryRepository mailDeliveryRepository,
                               ObjectProvider<JavaMailSender> mailSenderProvider,
                               PlatformTransactionManager transactionManager,
                               @Value("${spring.mail.host:}") String mailHost) {
        this.appMailProperties = appMailProperties;
        this.appTrackingProperties = appTrackingProperties;
        this.contactService = contactService;
        this.contactRepository = contactRepository;
        this.generatedFileService = generatedFileService;
        this.attachmentPersonalizer = attachmentPersonalizer;
        this.tokenFactory = tokenFactory;
        this.mailBatchRepository = mailBatchRepository;
        this.mailDeliveryRepository = mailDeliveryRepository;
        this.mailSenderProvider = mailSenderProvider;
        this.mailHost = mailHost;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    private enum Outcome { SENT, FAILED, BLOCKED }

    /**
     * Unveraenderlicher Versandkontext fuer die per-Empfaenger-Verarbeitung. Enthaelt bewusst KEINEN
     * Klartext-Token: dieser wird erst je Zustellung erzeugt und existiert nur kurzzeitig lokal.
     */
    private record SendContext(String from, String subject, String body, JavaMailSender mailSender,
                               byte[] attachmentBytes, String attachmentFilename, String attachmentContentType,
                               GeneratedFileType attachmentType, boolean insertTrackingLink, String trackingBaseUrl) {
    }

    /**
     * Prueft (nur lesend), ob der Composer grundsaetzlich versandbereit ist (Live-Send, SMTP konfiguriert,
     * gueltiger konfigurierter Absender). Empfaengerauswahl/Betreff/Text werden erst beim Versand geprueft.
     */
    @Transactional(readOnly = true)
    public MailComposeReadiness checkReadiness() {
        List<String> blockers = new ArrayList<>();
        if (!appMailProperties.isLiveSendEnabled()) {
            blockers.add("Testmodus: echter Versand ist deaktiviert (APP_MAIL_LIVE_SEND_ENABLED=false).");
        }
        if (!StringUtils.hasText(mailHost) || mailSenderProvider.getIfAvailable() == null) {
            blockers.add("SMTP ist nicht konfiguriert (MAIL_HOST fehlt).");
        }
        String sender = appMailProperties.getDefaultSender();
        if (!StringUtils.hasText(sender)) {
            blockers.add("Kein Absender konfiguriert (APP_MAIL_DEFAULT_SENDER fehlt).");
        } else if (!appMailProperties.isSenderAllowed(sender)) {
            blockers.add("Konfigurierter Absender ist nicht in der Allowlist erlaubt.");
        }
        boolean ready = blockers.isEmpty();
        return new MailComposeReadiness(ready, blockers, sender, appMailProperties.getDefaultSenderName(),
                appMailProperties.isLiveSendEnabled());
    }

    /**
     * Versendet die Nachricht an die ausgewaehlten Kontakte - je Kontakt genau eine Einzelmail. Vor dem
     * Versand werden alle Vorbedingungen geprueft; ist etwas verletzt, wird {@link SendNotAllowedException}
     * geworfen und NICHTS versendet (kein Batch angelegt). Unbekannte/manipulierte Kontakt- oder Datei-IDs
     * fuehren zu einer {@code *NotFoundException} (kein Versand). Ein einzelner Fehlschlag stoppt die
     * uebrigen Empfaenger nicht.
     */
    public MailComposeSummary send(MailSendRequest request) {
        MailComposeReadiness readiness = checkReadiness();
        List<String> blockers = new ArrayList<>(readiness.blockers());
        if (!StringUtils.hasText(request.subject())) {
            blockers.add("Betreff fehlt.");
        }
        if (!StringUtils.hasText(request.body())) {
            blockers.add("E-Mail-Text fehlt.");
        }
        List<Long> contactIds = request.contactIds();
        if (contactIds == null || contactIds.isEmpty()) {
            blockers.add("Kein Empfaenger ausgewaehlt.");
        }
        // Nur wenn ein sichtbarer Trainingslink eingefuegt werden soll, ist eine gueltige Basis-URL noetig
        // (die Tracking-Identitaet je Zustellung wird unabhaengig davon immer erzeugt).
        if (request.insertTrackingLink()
                && !TrackingLinkPolicy.isAcceptableBaseUrl(appTrackingProperties.getBaseUrl())) {
            blockers.add("Trainingslink aktiviert, aber die Tracking-Basis-URL ist ungueltig oder fehlt "
                    + "(APP_TRACKING_BASE_URL).");
        }
        if (!blockers.isEmpty()) {
            // Fail-closed: nichts versenden, keinen Batch anlegen.
            throw new SendNotAllowedException(blockers);
        }

        // Empfaenger serverseitig aufloesen/validieren (manipulierte/unbekannte IDs -> ContactNotFoundException).
        List<Contact> contacts = contactService.resolveSelected(contactIds);

        // Optionalen Anhang ausschliesslich ueber die interne Id aufloesen (unbekannt -> GeneratedFileNotFoundException).
        GeneratedFile attachment = null;
        byte[] attachmentBytes = null;
        if (request.generatedFileId() != null) {
            attachment = generatedFileService.getById(request.generatedFileId());
            attachmentBytes = generatedFileService.loadContent(attachment);
        }
        String attachmentFilename = attachment == null ? null : attachment.getDownloadFilename();
        String attachmentContentType = attachment == null ? null : attachment.getContentType();
        GeneratedFileType attachmentType = attachment == null ? null : attachment.getFileType();
        Long attachmentId = attachment == null ? null : attachment.getId();

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        String from = buildFrom(readiness.senderEmail(), readiness.senderName());
        String subject = request.subject();
        String body = request.body();

        // Batch in eigener Transaktion anlegen und committen, damit die per-Empfaenger-Zustellungen
        // unabhaengig committen koennen (Teilerfolge bleiben erhalten).
        final Long fAttachmentId = attachmentId;
        final String fAttachmentFilename = attachmentFilename;
        final int recipientCount = contacts.size();
        Long batchId = txTemplate.execute(status -> {
            GeneratedFile ref = fAttachmentId == null ? null : generatedFileService.getById(fAttachmentId);
            // In der Historie die reine Absenderadresse und den Anzeigenamen getrennt festhalten (nicht den
            // zusammengesetzten "Name <adresse>"-Header).
            MailBatch batch = new MailBatch(subject, body, readiness.senderEmail(), readiness.senderName(), ref,
                    fAttachmentFilename, recipientCount);
            return mailBatchRepository.saveAndFlush(batch).getId();
        });

        SendContext context = new SendContext(from, subject, body, mailSender, attachmentBytes,
                attachmentFilename, attachmentContentType, attachmentType, request.insertTrackingLink(),
                appTrackingProperties.getBaseUrl());

        int sent = 0;
        int failed = 0;
        int blocked = 0;
        List<String> failedEmails = new ArrayList<>();
        List<String> blockedEmails = new ArrayList<>();

        for (Contact contact : contacts) {
            Long contactId = contact.getId();
            String email = contact.getEmail();
            Outcome outcome = txTemplate.execute(status -> processOne(batchId, contactId, email, context));
            switch (outcome) {
                case SENT -> sent++;
                case FAILED -> {
                    failed++;
                    failedEmails.add(email);
                }
                case BLOCKED -> {
                    blocked++;
                    blockedEmails.add(email);
                }
                default -> { /* unreachable */ }
            }
        }

        return new MailComposeSummary(batchId, contacts.size(), sent, failed, blocked, failedEmails, blockedEmails);
    }

    /** Alle Versandvorgaenge fuer die Historie, neueste zuerst. */
    @Transactional(readOnly = true)
    public List<MailBatch> history() {
        return mailBatchRepository.findAllByOrderByCreatedAtDescIdDesc();
    }

    /** Laedt einen Versandvorgang oder wirft {@link MailBatchNotFoundException} (fuer die Detailseite/404). */
    @Transactional(readOnly = true)
    public MailBatch getBatch(Long batchId) {
        return mailBatchRepository.findById(batchId)
                .orElseThrow(() -> new MailBatchNotFoundException(batchId));
    }

    /** Die konfigurierte Tracking-Basis-URL (KEIN Secret) fuer die Anzeige im Composer. */
    public String trackingBaseUrl() {
        return appTrackingProperties.getBaseUrl();
    }

    /** Ob eine gueltige Tracking-Basis-URL konfiguriert ist (nur dann ist ein Trainingslink moeglich). */
    public boolean trackingConfigured() {
        return TrackingLinkPolicy.isAcceptableBaseUrl(appTrackingProperties.getBaseUrl());
    }

    /** Ob SMTP konfiguriert ist (Host gesetzt und ein JavaMailSender verfuegbar) - fuer die Composer-Statusanzeige. */
    public boolean smtpConfigured() {
        return StringUtils.hasText(mailHost) && mailSenderProvider.getIfAvailable() != null;
    }

    /** Ob der konfigurierte Standard-Absender durch die Allowlist erlaubt ist. */
    public boolean senderAllowed() {
        return appMailProperties.hasUsableDefaultSender();
    }

    /** Ob echter SMTP-Versand aktiviert ist (nur Statusanzeige). */
    public boolean liveSendEnabled() {
        return appMailProperties.isLiveSendEnabled();
    }

    /**
     * Baut eine reine VORSCHAU (Dry-Run) fuer die aktuelle Auswahl - OHNE Versand und OHNE jede Persistenz:
     * kein MailBatch, keine MailDelivery, kein TrackingEvent, KEIN persistenter Tracking-Token, keine
     * SMTP-Verbindung. Empfaenger und optionaler Anhang werden ausschliesslich ueber ihre internen IDs
     * aufgeloest und validiert (manipulierte/unbekannte IDs -&gt; {@code *NotFoundException}). Fuer das Tracking
     * wird nur ein Platzhalterlink angezeigt, KEIN echter Token erzeugt.
     */
    @Transactional(readOnly = true)
    public MailPreview preview(MailSendRequest request) {
        MailComposeReadiness readiness = checkReadiness();
        List<Contact> contacts = contactService.resolveSelected(request.contactIds());
        GeneratedFile file = request.generatedFileId() == null ? null
                : generatedFileService.getById(request.generatedFileId());

        boolean tracking = request.insertTrackingLink();
        String baseUrl = appTrackingProperties.getBaseUrl();
        String sampleLink = (tracking && StringUtils.hasText(baseUrl))
                ? baseUrl.replaceAll("/+$", "") + "/t/<individueller-token>" : null;

        return new MailPreview(
                readiness.senderEmail(), readiness.senderName(),
                request.subject(), request.body(),
                contacts.size(), contacts,
                file != null,
                file == null ? null : file.getDisplayName(),
                file == null ? null : file.getDownloadFilename(),
                file == null ? null : file.getFileType().name(),
                file == null ? 0L : file.getFileSize(),
                file == null ? null : file.getId(),
                tracking, baseUrl, sampleLink,
                appMailProperties.isLiveSendEnabled());
    }

    /**
     * Ein Versandvorgang mit abgeleiteter Zustell-Statistik fuer die Historie-Ansicht.
     *
     * @param batch   der Versandvorgang
     * @param sent    Anzahl erfolgreich zugestellter Empfaenger
     * @param failed  Anzahl fehlgeschlagener Zustellungen
     * @param blocked Anzahl durch die Empfaenger-Allowlist blockierter Empfaenger (NOT_SENT)
     */
    public record MailBatchStats(MailBatch batch, long sent, long failed, long blocked) {
    }

    /** Alle Versandvorgaenge mit Zustell-Statistik fuer die Historie, neueste zuerst. */
    @Transactional(readOnly = true)
    public List<MailBatchStats> historyWithStats() {
        List<MailBatchStats> rows = new ArrayList<>();
        for (MailBatch batch : mailBatchRepository.findAllByOrderByCreatedAtDescIdDesc()) {
            long sent = mailDeliveryRepository.countByBatchAndStatus(batch, DeliveryStatus.SENT);
            long failed = mailDeliveryRepository.countByBatchAndStatus(batch, DeliveryStatus.FAILED);
            long notSent = mailDeliveryRepository.countByBatchAndStatus(batch, DeliveryStatus.NOT_SENT);
            rows.add(new MailBatchStats(batch, sent, failed, notSent));
        }
        return rows;
    }

    /**
     * Verarbeitet genau einen Empfaenger in der aktuellen (pro-Empfaenger-)Transaktion: eindeutige
     * Tracking-Identitaet erzeugen (nur der Hash wird gespeichert), Zustellung anlegen, Allowlist pruefen und
     * - falls erlaubt - genau eine MimeMessage (ein To, nie CC/BCC, optional ein Anhang) senden. Bei aktivem
     * Trainingslink wird der individuelle Link in den Text und - bei DOCX/XML-Anhang - in eine individualisierte
     * Anhangskopie eingefuegt. Der Klartext-Token existiert nur lokal in dieser Methode; er wird NIE
     * gespeichert oder geloggt. Regulaere Sendefehler werden abgefangen (Zustellung FAILED, Tx committet).
     */
    private Outcome processOne(Long batchId, Long contactId, String email, SendContext ctx) {
        MailBatch batch = mailBatchRepository.findById(batchId).orElseThrow();
        Contact contact = contactRepository.findById(contactId).orElseThrow();
        // Jede Zustellung erhaelt eine eigene, nicht erratbare Tracking-Identitaet; persistiert wird nur der Hash.
        TrackingTokens.GeneratedToken token = newUniqueToken();
        MailDelivery delivery = new MailDelivery(batch, contact, token.tokenHash());

        if (!appMailProperties.isRecipientDomainAllowed(email)) {
            delivery.recordBlocked(BLOCKED_CATEGORY);
            mailDeliveryRepository.saveAndFlush(delivery);
            return Outcome.BLOCKED;
        }

        delivery.recordAttempt();
        try {
            // Klartext-Token nur lokal verwenden (Link bauen), danach verworfen - nie speichern/loggen.
            String trainingUrl = ctx.insertTrackingLink()
                    ? TrackingLinkPolicy.buildTrackingUrl(ctx.trackingBaseUrl(), token.token()) : null;
            String body = ctx.insertTrackingLink() ? appendTrainingLink(ctx.body(), trainingUrl) : ctx.body();

            byte[] attachmentBytes = ctx.attachmentBytes();
            if (attachmentBytes != null && ctx.insertTrackingLink() && ctx.attachmentType() != null) {
                // Individualisierte KOPIE fuer genau diesen Empfaenger (Original der Bibliothek bleibt unveraendert).
                attachmentBytes = attachmentPersonalizer.personalize(ctx.attachmentType(),
                        ctx.attachmentBytes(), trainingUrl);
            }

            MimeMessage message = ctx.mailSender().createMimeMessage();
            boolean multipart = attachmentBytes != null;
            MimeMessageHelper helper = new MimeMessageHelper(message, multipart, "UTF-8");
            helper.setFrom(ctx.from());
            helper.setTo(email);
            helper.setSubject(ctx.subject());
            helper.setText(body, false);
            if (multipart) {
                helper.addAttachment(ctx.attachmentFilename(), new ByteArrayResource(attachmentBytes),
                        ctx.attachmentContentType());
            }
            ctx.mailSender().send(message);

            delivery.recordSent(Instant.now());
            mailDeliveryRepository.saveAndFlush(delivery);
            return Outcome.SENT;
        } catch (Exception ex) {
            String category = MailFailureCategory.of(ex);
            delivery.recordFailure(category);
            mailDeliveryRepository.saveAndFlush(delivery);
            log.warn("Versand fehlgeschlagen fuer Zustellung contactId={}, Kategorie={}", contactId, category);
            return Outcome.FAILED;
        }
    }

    /** Haengt den sichtbaren Trainingslink als eigene Zeile unter den Plaintext-Koerper. */
    private static String appendTrainingLink(String body, String url) {
        return body + "\n\n" + TRAINING_LINK_LABEL + url;
    }

    /**
     * Erzeugt eine Tracking-Identitaet, deren Hash noch nicht vergeben ist (hoechstens
     * {@link #MAX_TOKEN_ATTEMPTS} Versuche; Kollision bei 256 Bit praktisch unmoeglich). Der Unique-Index
     * auf {@code tracking_token_hash} bleibt der endgueltige Schutz.
     */
    private TrackingTokens.GeneratedToken newUniqueToken() {
        for (int attempt = 1; attempt <= MAX_TOKEN_ATTEMPTS; attempt++) {
            TrackingTokens.GeneratedToken candidate = tokenFactory.newToken();
            if (!mailDeliveryRepository.existsByTrackingTokenHash(candidate.tokenHash())) {
                return candidate;
            }
        }
        throw new IllegalStateException("Konnte keine eindeutige Tracking-Identitaet erzeugen.");
    }

    /**
     * Baut den Absender-Header: mit Anzeigename {@code "Name <adresse>"}, sonst nur die Adresse. Der
     * Anzeigename wird defensiv von CR/LF befreit (Schutz gegen Header-Injektion). Es werden ausschliesslich
     * die (nicht sensiblen) konfigurierten Absenderdaten verwendet.
     */
    private static String buildFrom(String senderEmail, String senderName) {
        if (StringUtils.hasText(senderName)) {
            String sanitizedName = senderName.replaceAll("[\\r\\n]", " ").trim();
            return String.format("%s <%s>", sanitizedName, senderEmail);
        }
        return senderEmail;
    }
}
