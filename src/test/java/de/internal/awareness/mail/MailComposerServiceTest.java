package de.internal.awareness.mail;

import de.internal.awareness.contact.Contact;
import de.internal.awareness.contact.ContactNotFoundException;
import de.internal.awareness.contact.ContactService;
import de.internal.awareness.file.GeneratedFile;
import de.internal.awareness.file.GeneratedFileNotFoundException;
import de.internal.awareness.file.GeneratedFileService;
import de.internal.awareness.file.GeneratedFileType;
import de.internal.awareness.recipient.DeliveryStatus;
import de.internal.awareness.support.RecordedMimeMail;
import de.internal.awareness.support.RecordingJavaMailSender;
import de.internal.awareness.support.TestMailConfig;
import de.internal.awareness.tracking.TrackingTokens;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Versandtests fuer {@link MailComposerService} gegen die reale (isolierte) SQLite-Test-DB, ein Temp-
 * Datenverzeichnis und ein aufzeichnendes {@link RecordingJavaMailSender} (kein echtes SMTP).
 *
 * <p>Deckt u. a. ab: Absender aus Konfiguration (nicht ueberschreibbar), Einzel-/Mehrfachauswahl,
 * 0-Empfaenger-Ablehnung, Ablehnung manipulierter Kontakt-/Datei-IDs, Versand mit/ohne Anhang (DOCX/XML),
 * genau eine Mail je Empfaenger ohne CC/BCC, korrekter Anhang-Name/-Content-Type, Robustheit gegen einzelne
 * Fehlschlaege, Live-Send-Schutz, Sender-/Empfaenger-Allowlist sowie Batch-/Delivery-Historie.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
@Import(TestMailConfig.class)
@TestPropertySource(properties = {
        "spring.mail.host=smtp.example.invalid",
        "spring.mail.password=DoNotLeakThisSecret123"
})
class MailComposerServiceTest {

    private static final String SENDER_EMAIL = "training@example.invalid";
    private static final String SENDER_NAME = "IT Security";
    private static final String SUBJECT = "Wichtige Information";
    private static final String BODY = "Hallo, dies ist ein autorisierter interner Test.";

    @TempDir
    static Path generatedDir;

    @DynamicPropertySource
    static void fileProps(DynamicPropertyRegistry registry) {
        registry.add("app.files.generated-dir", () -> generatedDir.toString());
    }

    @Autowired
    private MailComposerService service;

    @Autowired
    private ContactService contactService;

    @Autowired
    private GeneratedFileService generatedFileService;

    @Autowired
    private de.internal.awareness.config.AppMailProperties appMailProperties;

    @Autowired
    private RecordingJavaMailSender recordingJavaMailSender;

    @Autowired
    private MailBatchRepository mailBatchRepository;

    @Autowired
    private MailDeliveryRepository mailDeliveryRepository;

    @Autowired
    private de.internal.awareness.config.AppTrackingProperties appTrackingProperties;

    private static final String TRACKING_BASE_URL = "https://training.example.invalid";

    @BeforeEach
    void setUp() {
        recordingJavaMailSender.reset();
        appMailProperties.setLiveSendEnabled(true);
        appMailProperties.setAllowedSenders(List.of(SENDER_EMAIL));
        appMailProperties.setDefaultSender(SENDER_EMAIL);
        appMailProperties.setDefaultSenderName(SENDER_NAME);
        appMailProperties.setAllowedRecipientDomains(List.of());
        appTrackingProperties.setBaseUrl(TRACKING_BASE_URL);
    }

    private Long contact(String email) {
        return contactService.addContact(email, "Name " + email).getId();
    }

    private MailSendRequest request(Long fileId, List<Long> contactIds) {
        return new MailSendRequest(SUBJECT, BODY, fileId, contactIds, false);
    }

    private MailSendRequest trackingRequest(Long fileId, List<Long> contactIds) {
        return new MailSendRequest(SUBJECT, BODY, fileId, contactIds, true);
    }

    /** Extrahiert den Klartext-Token aus einer Trainingslink-Zeile "..../t/<token>" im Mail-Text. */
    private static String tokenFromBody(String body) {
        int idx = body.lastIndexOf("/t/");
        assertThat(idx).as("Body enthaelt einen Trainingslink").isGreaterThanOrEqualTo(0);
        return body.substring(idx + 3).trim();
    }

    // --- Absender aus Konfiguration (#22/#23) ---

    @Test
    void senderComesFromConfigurationAndCannotBeOverridden() {
        Long c = contact("a@example.invalid");

        service.send(request(null, List.of(c)));

        RecordedMimeMail mail = recordingJavaMailSender.getSentMimeMails().get(0);
        assertThat(mail.from()).isEqualTo(SENDER_NAME + " <" + SENDER_EMAIL + ">");
    }

    // --- Auswahl (#24/#25) ---

    @Test
    void sendsToSingleSelectedRecipient() {
        Long c = contact("single@example.invalid");

        MailComposeSummary summary = service.send(request(null, List.of(c)));

        assertThat(summary.sent()).isEqualTo(1);
        assertThat(recordingJavaMailSender.getSentMimeCount()).isEqualTo(1);
    }

    @Test
    void sendsToMultipleSelectedRecipients() {
        List<Long> ids = List.of(contact("m1@example.invalid"), contact("m2@example.invalid"),
                contact("m3@example.invalid"));

        MailComposeSummary summary = service.send(request(null, ids));

        assertThat(summary.sent()).isEqualTo(3);
        assertThat(recordingJavaMailSender.getSentMimeCount()).isEqualTo(3);
    }

    // --- 0 Empfaenger (#27) ---

    @Test
    void zeroRecipientsIsRejectedAndSendsNothing() {
        assertThatThrownBy(() -> service.send(request(null, List.of())))
                .isInstanceOf(SendNotAllowedException.class);
        assertThat(recordingJavaMailSender.getSentMimeCount()).isZero();
        assertThat(mailBatchRepository.count()).isZero();
    }

    // --- Manipulierte IDs (#28/#30) ---

    @Test
    void unknownContactIdIsRejectedAndSendsNothing() {
        Long valid = contact("ok@example.invalid");

        assertThatThrownBy(() -> service.send(request(null, List.of(valid, 999_999L))))
                .isInstanceOf(ContactNotFoundException.class);
        assertThat(recordingJavaMailSender.getSentMimeCount()).isZero();
        assertThat(mailBatchRepository.count()).isZero();
    }

    @Test
    void unknownGeneratedFileIdIsRejectedAndSendsNothing() {
        Long c = contact("ok@example.invalid");

        assertThatThrownBy(() -> service.send(request(999_999L, List.of(c))))
                .isInstanceOf(GeneratedFileNotFoundException.class);
        assertThat(recordingJavaMailSender.getSentMimeCount()).isZero();
        assertThat(mailBatchRepository.count()).isZero();
    }

    // --- Anhang (#29/#31/#32/#33/#36/#37) ---

    @Test
    void sendsWithoutAttachment() {
        Long c = contact("a@example.invalid");

        service.send(request(null, List.of(c)));

        RecordedMimeMail mail = recordingJavaMailSender.getSentMimeMails().get(0);
        assertThat(mail.attachments()).isEmpty();
        assertThat(mail.body()).isEqualTo(BODY);
    }

    @Test
    void sendsWithDocxAttachment() {
        GeneratedFile file = generatedFileService.createDocx("Rechnung", "rechnung", "Titel", null, "Inhalt");
        Long c = contact("a@example.invalid");

        service.send(request(file.getId(), List.of(c)));

        RecordedMimeMail mail = recordingJavaMailSender.getSentMimeMails().get(0);
        assertThat(mail.attachments()).hasSize(1);
        RecordedMimeMail.Attachment att = mail.attachments().get(0);
        assertThat(att.filename()).isEqualTo("rechnung.docx");
        assertThat(att.baseContentType()).isEqualTo(GeneratedFileType.DOCX.contentType());
        assertThat(att.bytes()).isNotEmpty();
    }

    @Test
    void sendsWithXmlAttachment() {
        GeneratedFile file = generatedFileService.createXml("Export", "export", "daten", "T", "C");
        Long c = contact("a@example.invalid");

        service.send(request(file.getId(), List.of(c)));

        RecordedMimeMail.Attachment att = recordingJavaMailSender.getSentMimeMails().get(0).attachments().get(0);
        assertThat(att.filename()).isEqualTo("export.xml");
        assertThat(att.baseContentType()).isEqualTo(GeneratedFileType.XML.contentType());
    }

    // --- Genau eine Mail je Empfaenger, kein CC/BCC (#34/#35) ---

    @Test
    void oneMailPerRecipientWithSingleToAndNoCcBcc() {
        List<Long> ids = List.of(contact("x1@example.invalid"), contact("x2@example.invalid"),
                contact("x3@example.invalid"));

        service.send(request(null, ids));

        List<RecordedMimeMail> mails = recordingJavaMailSender.getSentMimeMails();
        assertThat(mails).hasSize(3);
        for (RecordedMimeMail mail : mails) {
            assertThat(mail.to()).hasSize(1);
            assertThat(mail.cc()).isEmpty();
            assertThat(mail.bcc()).isEmpty();
            assertThat(mail.subject()).isEqualTo(SUBJECT);
        }
        assertThat(mails).flatExtracting(RecordedMimeMail::to)
                .containsExactlyInAnyOrder("x1@example.invalid", "x2@example.invalid", "x3@example.invalid");
    }

    // --- Robustheit gegen einzelne Fehlschlaege (#38) ---

    @Test
    void oneFailingRecipientDoesNotStopOthers() {
        Long ok1 = contact("ok1@example.invalid");
        Long boom = contact("boom@example.invalid");
        Long ok2 = contact("ok2@example.invalid");
        recordingJavaMailSender.failForRecipients("boom@example.invalid");

        MailComposeSummary summary = service.send(request(null, List.of(ok1, boom, ok2)));

        assertThat(summary.sent()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.failedEmails()).containsExactly("boom@example.invalid");
        assertThat(recordingJavaMailSender.getSentMimeCount()).isEqualTo(2);
    }

    // --- Live-Send-Schutz (#39) ---

    @Test
    void liveSendDisabledBlocksSendAndStoresNoBatch() {
        appMailProperties.setLiveSendEnabled(false);
        Long c = contact("a@example.invalid");

        assertThatThrownBy(() -> service.send(request(null, List.of(c))))
                .isInstanceOf(SendNotAllowedException.class);
        assertThat(recordingJavaMailSender.getSentMimeCount()).isZero();
        assertThat(mailBatchRepository.count()).isZero();
    }

    // --- Sender-Allowlist (#40) ---

    @Test
    void senderNotInAllowlistBlocksSend() {
        appMailProperties.setAllowedSenders(List.of("someone-else@example.invalid"));
        Long c = contact("a@example.invalid");

        MailComposeReadiness readiness = service.checkReadiness();
        assertThat(readiness.ready()).isFalse();
        assertThatThrownBy(() -> service.send(request(null, List.of(c))))
                .isInstanceOf(SendNotAllowedException.class);
        assertThat(recordingJavaMailSender.getSentMimeCount()).isZero();
    }

    // --- Empfaenger-Allowlist (#41) ---

    @Test
    void recipientDomainAllowlistBlocksOtherDomain() {
        appMailProperties.setAllowedRecipientDomains(List.of("example.invalid"));
        Long ok = contact("ok@example.invalid");
        Long blocked = contact("x@other.invalid");

        MailComposeSummary summary = service.send(request(null, List.of(ok, blocked)));

        assertThat(summary.sent()).isEqualTo(1);
        assertThat(summary.blocked()).isEqualTo(1);
        assertThat(summary.blockedEmails()).containsExactly("x@other.invalid");
        assertThat(recordingJavaMailSender.getSentMimeCount()).isEqualTo(1);

        List<MailDelivery> deliveries = mailDeliveryRepository.findByBatch(
                mailBatchRepository.findById(summary.batchId()).orElseThrow());
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries).anySatisfy(d -> {
            assertThat(d.getContact().getEmail()).isEqualTo("x@other.invalid");
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.NOT_SENT);
            assertThat(d.getFailureCategory()).isEqualTo("BLOCKED");
        });
    }

    // --- Historie (#44/#45) ---

    @Test
    void batchAndDeliveriesArePersisted() {
        GeneratedFile file = generatedFileService.createDocx("Rechnung", "rechnung", "T", null, "B");
        List<Long> ids = List.of(contact("h1@example.invalid"), contact("h2@example.invalid"));

        MailComposeSummary summary = service.send(request(file.getId(), ids));

        MailBatch batch = mailBatchRepository.findById(summary.batchId()).orElseThrow();
        assertThat(batch.getSubject()).isEqualTo(SUBJECT);
        assertThat(batch.getSenderEmail()).isEqualTo(SENDER_EMAIL);
        assertThat(batch.getRecipientCount()).isEqualTo(2);
        assertThat(batch.getAttachmentFilename()).isEqualTo("rechnung.docx");
        assertThat(batch.getGeneratedFile()).isNotNull();

        List<MailDelivery> deliveries = mailDeliveryRepository.findByBatch(batch);
        assertThat(deliveries).hasSize(2);
        assertThat(mailDeliveryRepository.countByBatchAndStatus(batch, DeliveryStatus.SENT)).isEqualTo(2L);
        assertThat(deliveries).allSatisfy(d -> {
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.SENT);
            assertThat(d.getSentAt()).isNotNull();
            assertThat(d.getAttemptCount()).isEqualTo(1);
        });
        assertThat(service.history()).extracting(MailBatch::getId).contains(batch.getId());
    }

    // --- Kein Secret-Leak in der Zusammenfassung ---

    @Test
    void summaryDoesNotLeakSmtpPassword() {
        Long c = contact("a@example.invalid");
        MailComposeSummary summary = service.send(request(null, List.of(c)));
        assertThat(summary.toString()).doesNotContain("DoNotLeakThisSecret123");
    }

    // --- Tracking: Trainingslink im Text (#21/#22/#24/#27) ---

    @Test
    void trackingDisabledInsertsNoLink() {
        Long c = contact("a@example.invalid");
        service.send(request(null, List.of(c)));
        RecordedMimeMail mail = recordingJavaMailSender.getSentMimeMails().get(0);
        assertThat(mail.body()).isEqualTo(BODY).doesNotContain("/t/");
    }

    @Test
    void trackingEnabledInsertsIndividualLinkPerRecipient() {
        List<Long> ids = List.of(contact("a@example.invalid"), contact("b@example.invalid"));
        service.send(trackingRequest(null, ids));

        List<RecordedMimeMail> mails = recordingJavaMailSender.getSentMimeMails();
        assertThat(mails).hasSize(2);
        for (RecordedMimeMail mail : mails) {
            assertThat(mail.body()).contains(TRACKING_BASE_URL + "/t/");
            // Jede Mail enthaelt genau EINEN Trainingslink (keinen fremden Empfaenger-Token).
            assertThat(mail.body().split("/t/", -1)).hasSize(2);
        }
        String tokenA = tokenFromBody(mails.get(0).body());
        String tokenB = tokenFromBody(mails.get(1).body());
        assertThat(tokenA).isNotEqualTo(tokenB);
    }

    @Test
    void trackingLinkUsesConfiguredBaseUrl() {
        Long c = contact("a@example.invalid");
        service.send(trackingRequest(null, List.of(c)));
        assertThat(recordingJavaMailSender.getSentMimeMails().get(0).body())
                .contains(TRACKING_BASE_URL + "/t/");
    }

    // --- Tracking: nur Hash gespeichert, Token ueber Hash auffindbar (#23/#3/#4) ---

    @Test
    void deliveryStoresOnlyHashThatMatchesTheLinkToken() {
        Long c = contact("a@example.invalid");
        MailComposeSummary summary = service.send(trackingRequest(null, List.of(c)));

        String token = tokenFromBody(recordingJavaMailSender.getSentMimeMails().get(0).body());
        MailBatch batch = mailBatchRepository.findById(summary.batchId()).orElseThrow();
        MailDelivery delivery = mailDeliveryRepository.findByBatch(batch).get(0);

        assertThat(delivery.getTrackingTokenHash()).hasSize(64).matches("[0-9a-f]{64}");
        // Nur der Hash ist gespeichert; er entspricht dem Hash des Link-Tokens.
        assertThat(TrackingTokens.hash(token)).isEqualTo(delivery.getTrackingTokenHash());
        assertThat(mailDeliveryRepository.findByTrackingTokenHash(delivery.getTrackingTokenHash())).isPresent();
        // Der Klartext-Token ist NICHT als Spaltenwert auffindbar.
        assertThat(mailDeliveryRepository.findByTrackingTokenHash(token)).isEmpty();
    }

    @Test
    void everyDeliveryGetsATokenEvenWhenLinkNotInserted() {
        List<Long> ids = List.of(contact("a@example.invalid"), contact("b@example.invalid"));
        MailComposeSummary summary = service.send(request(null, ids)); // Tracking-Link deaktiviert

        MailBatch batch = mailBatchRepository.findById(summary.batchId()).orElseThrow();
        assertThat(mailDeliveryRepository.findByBatch(batch))
                .allSatisfy(d -> assertThat(d.getTrackingTokenHash()).matches("[0-9a-f]{64}"))
                .extracting(MailDelivery::getTrackingTokenHash)
                .doesNotHaveDuplicates();
    }

    // --- Base-URL-Validierung (#25/#26) ---

    @Test
    void invalidTrackingBaseUrlWithLinkIsRejectedAndSendsNothing() {
        appTrackingProperties.setBaseUrl("javascript:alert(1)");
        Long c = contact("a@example.invalid");

        assertThatThrownBy(() -> service.send(trackingRequest(null, List.of(c))))
                .isInstanceOf(SendNotAllowedException.class);
        assertThat(recordingJavaMailSender.getSentMimeCount()).isZero();
        assertThat(mailBatchRepository.count()).isZero();
    }

    @Test
    void localhostTrackingBaseUrlIsAllowed() {
        appTrackingProperties.setBaseUrl("http://localhost:8080");
        Long c = contact("a@example.invalid");

        service.send(trackingRequest(null, List.of(c)));

        assertThat(recordingJavaMailSender.getSentMimeMails().get(0).body())
                .contains("http://localhost:8080/t/");
    }

    // --- Kein Token-Leak in Logs (#28) ---

    @Test
    void tokensAreNotLogged() {
        List<Long> ids = List.of(contact("a@example.invalid"), contact("b@example.invalid"));

        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
        try {
            service.send(trackingRequest(null, ids));
        } finally {
            rootLogger.detachAppender(appender);
            appender.stop();
        }
        // Keine Logzeile enthaelt den Trainingslink-Pfad (und damit keinen Klartext-Token).
        assertThat(appender.list)
                .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("/t/"));
    }

    // --- DOCX-Individualisierung (#29/#31/#32/#33/#37) ---

    @Test
    void docxAttachmentIsIndividualizedPerRecipientAndOriginalStaysUnchanged() throws Exception {
        GeneratedFile file = generatedFileService.createDocx("Rechnung", "rechnung", "Titel", null, "Inhalt");
        byte[] originalBefore = generatedFileService.loadContent(file);
        long filesBefore = generatedFileService.findAll().size();
        List<Long> ids = List.of(contact("a@example.invalid"), contact("b@example.invalid"));

        service.send(trackingRequest(file.getId(), ids));

        List<RecordedMimeMail> mails = recordingJavaMailSender.getSentMimeMails();
        assertThat(mails).hasSize(2);
        String textA = docxText(mails.get(0).attachments().get(0).bytes());
        String textB = docxText(mails.get(1).attachments().get(0).bytes());
        // Jede Versandkopie enthaelt den sichtbaren, individuellen Trainingslink.
        assertThat(textA).contains(TRACKING_BASE_URL + "/t/");
        assertThat(textB).contains(TRACKING_BASE_URL + "/t/");
        assertThat(textA).isNotEqualTo(textB);

        // Das Original der Bibliothek bleibt unveraendert und es wurde keine Datei zusaetzlich gespeichert.
        assertThat(generatedFileService.loadContent(file)).isEqualTo(originalBefore);
        assertThat(generatedFileService.findAll()).hasSize((int) filesBefore);
    }

    // --- XML-Individualisierung (#38, Detailpruefung in XmlTrackingPersonalizerTest) ---

    @Test
    void xmlAttachmentContainsTrainingLinkAsTextElement() {
        GeneratedFile file = generatedFileService.createXml("Export", "export", "daten", "T", "C");
        Long c = contact("a@example.invalid");

        service.send(trackingRequest(file.getId(), List.of(c)));

        String xml = new String(recordingJavaMailSender.getSentMimeMails().get(0).attachments().get(0).bytes(),
                StandardCharsets.UTF_8);
        assertThat(xml).contains("<trainingLink>").contains(TRACKING_BASE_URL + "/t/");
        assertThat(xml).doesNotContain("<!DOCTYPE").doesNotContain("<!ENTITY");
    }

    private static String docxText(byte[] docx) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }
}
