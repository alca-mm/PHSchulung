package de.internal.awareness.mail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.internal.awareness.campaign.CampaignService;
import de.internal.awareness.config.AppMailProperties;
import de.internal.awareness.recipient.CampaignRecipient;
import de.internal.awareness.recipient.CampaignRecipientRepository;
import de.internal.awareness.recipient.DeliveryStatus;
import de.internal.awareness.recipient.RecipientService;
import de.internal.awareness.support.RecordingJavaMailSender;
import de.internal.awareness.support.TestMailConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Versandtests fuer {@link CampaignMailService} gegen die reale (isolierte) SQLite-Test-DB und ein
 * aufzeichnendes {@link RecordingJavaMailSender}-Doppel (kein echtes SMTP).
 *
 * <p>Faelle 12-28: Einzelmail je Empfaenger (keine Sammel-To/CC/BCC), korrekte Kopfdaten (From/Betreff/
 * Text), Statuspflege (SENT/FAILED, {@code sentAt}, {@code attemptCount}), Robustheit gegen einzelne
 * Fehlschlaege, Ueberspringen bereits versendeter und erneuter Versuch fehlgeschlagener Empfaenger,
 * die drei Sicherheitsschalter (Live-Send, Empfaenger-Domain-Allowlist, Absender-Allowlist) sowie der
 * Nachweis, dass kein SMTP-Passwort leakt.</p>
 *
 * <p>{@code spring.mail.host} ist gesetzt, damit die Host-Pruefung in {@code checkReadiness} passiert; das
 * RecordingJavaMailSender-Doppel verbindet sich dennoch nie mit einem echten Server.</p>
 *
 * <p>Wichtige Falle: pro Testmethode/Transaktion nie zwei fehlschlagende Bean-Validation-Flushes. Hier
 * werden Fehler ausschliesslich ueber {@link RecordingJavaMailSender#failForRecipients} simuliert; der
 * anschliessende Flush eines FAILED-Empfaengers ist ein regulaerer, gueltiger Flush und daher unkritisch.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
@Import(TestMailConfig.class)
@TestPropertySource(properties = {
        "spring.mail.host=smtp.example.invalid",
        "spring.mail.password=DoNotLeakThisSecret123"
})
class CampaignMailServiceTest {

    private static final String SECRET = "DoNotLeakThisSecret123";
    private static final String SENDER_EMAIL = "training@example.invalid";
    private static final String SENDER_NAME = "IT Security";
    private static final String SUBJECT = "Betreff";
    private static final String BODY = "Hallo, dies ist ein Test.";

    @Autowired
    private CampaignMailService campaignMailService;

    @Autowired
    private CampaignService campaignService;

    @Autowired
    private RecipientService recipientService;

    @Autowired
    private AppMailProperties appMailProperties;

    @Autowired
    private RecordingJavaMailSender recordingJavaMailSender;

    @Autowired
    private CampaignRecipientRepository recipientRepository;

    @BeforeEach
    void setUp() {
        recordingJavaMailSender.reset();
        // Sichere Defaults fuer eine versandbereite Kampagne.
        appMailProperties.setLiveSendEnabled(true);
        appMailProperties.setAllowedSenders(List.of(SENDER_EMAIL));
        appMailProperties.setAllowedRecipientDomains(List.of());
    }

    // --- Hilfsmethoden -------------------------------------------------------

    /** Legt eine versandbereite Kampagne (erlaubter Absender, Betreff, Text) an und gibt ihre Id zurueck. */
    private Long readyCampaign() {
        return campaignService.create("Kampagne", null, SENDER_NAME, SENDER_EMAIL, SUBJECT, BODY).getId();
    }

    /** Fuegt einen Empfaenger hinzu und gibt dessen Id zurueck. */
    private Long addRecipient(Long campaignId, String email) {
        return recipientService.addRecipient(campaignId, email, "Name " + email).recipient().getId();
    }

    private CampaignRecipient reload(Long recipientId) {
        return recipientRepository.findById(recipientId).orElseThrow();
    }

    // --- Faelle 12-28 --------------------------------------------------------

    /** Fall 12: Versand an genau einen Empfaenger erzeugt genau eine aufgezeichnete Nachricht. */
    @Test
    void sendsExactlyOneMessageForOneRecipient() {
        Long campaignId = readyCampaign();
        addRecipient(campaignId, "a@example.invalid");

        SendSummary summary = campaignMailService.sendToAll(campaignId);

        assertThat(recordingJavaMailSender.getSentCount()).isEqualTo(1);
        assertThat(summary.attempted()).isEqualTo(1);
        assertThat(summary.sent()).isEqualTo(1);
    }

    /** Fall 13: Versand an fuenf Empfaenger erzeugt genau fuenf aufgezeichnete Nachrichten. */
    @Test
    void sendsExactlyFiveMessagesForFiveRecipients() {
        Long campaignId = readyCampaign();
        for (int i = 0; i < 5; i++) {
            addRecipient(campaignId, "r" + i + "@example.invalid");
        }

        SendSummary summary = campaignMailService.sendToAll(campaignId);

        assertThat(recordingJavaMailSender.getSentCount()).isEqualTo(5);
        assertThat(summary.sent()).isEqualTo(5);
    }

    /** Fall 14: Jede Nachricht hat genau einen To-Empfaenger, kein CC/BCC; die To-Adresse ist die gewuenschte. */
    @Test
    void eachMessageHasSingleToAndNoCcOrBcc() {
        Long campaignId = readyCampaign();
        List<String> emails = List.of("x1@example.invalid", "x2@example.invalid", "x3@example.invalid");
        emails.forEach(email -> addRecipient(campaignId, email));

        campaignMailService.sendToAll(campaignId);

        List<SimpleMailMessage> messages = recordingJavaMailSender.getSentMessages();
        assertThat(messages).hasSize(3);
        for (SimpleMailMessage message : messages) {
            assertThat(message.getTo()).hasSize(1);
            assertThat(message.getCc()).isNull();
            assertThat(message.getBcc()).isNull();
        }
        assertThat(messages).extracting(m -> m.getTo()[0]).containsExactlyInAnyOrderElementsOf(emails);
    }

    /** Fall 15: From entspricht dem Kampagnen-Absender ("Name <adresse>", wenn ein Anzeigename gesetzt ist). */
    @Test
    void fromIsCampaignSenderWithDisplayName() {
        Long campaignId = readyCampaign();
        addRecipient(campaignId, "a@example.invalid");

        campaignMailService.sendToAll(campaignId);

        SimpleMailMessage message = recordingJavaMailSender.getSentMessages().get(0);
        assertThat(message.getFrom()).isEqualTo(SENDER_NAME + " <" + SENDER_EMAIL + ">");
    }

    /** Fall 16: Der Betreff ist auf jeder Nachricht korrekt gesetzt. */
    @Test
    void subjectIsCorrectOnEveryMessage() {
        Long campaignId = readyCampaign();
        addRecipient(campaignId, "a@example.invalid");
        addRecipient(campaignId, "b@example.invalid");

        campaignMailService.sendToAll(campaignId);

        assertThat(recordingJavaMailSender.getSentMessages())
                .isNotEmpty()
                .allSatisfy(message -> assertThat(message.getSubject()).isEqualTo(SUBJECT));
    }

    /** Fall 17: Der E-Mail-Text ist auf jeder Nachricht korrekt gesetzt. */
    @Test
    void bodyIsCorrectOnEveryMessage() {
        Long campaignId = readyCampaign();
        addRecipient(campaignId, "a@example.invalid");
        addRecipient(campaignId, "b@example.invalid");

        campaignMailService.sendToAll(campaignId);

        assertThat(recordingJavaMailSender.getSentMessages())
                .isNotEmpty()
                .allSatisfy(message -> assertThat(message.getText()).isEqualTo(BODY));
    }

    /** Fall 18: Erfolgreicher Versand setzt den Status auf SENT (nach Reload aus dem Repository). */
    @Test
    void successfulDeliverySetsStatusSent() {
        Long campaignId = readyCampaign();
        Long recipientId = addRecipient(campaignId, "a@example.invalid");

        campaignMailService.sendToAll(campaignId);

        assertThat(reload(recipientId).getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    /** Fall 19: Nach erfolgreichem Versand ist {@code sentAt} gesetzt. */
    @Test
    void successfulDeliverySetsSentAt() {
        Long campaignId = readyCampaign();
        Long recipientId = addRecipient(campaignId, "a@example.invalid");

        campaignMailService.sendToAll(campaignId);

        assertThat(reload(recipientId).getSentAt()).isNotNull();
    }

    /** Fall 20: Der Versuchszaehler wird erhoeht (==1 nach einem Versand; ==2 nach einem erneuten Versuch). */
    @Test
    void attemptCountIsIncremented() {
        Long campaignId = readyCampaign();
        String email = "count@example.invalid";
        Long recipientId = addRecipient(campaignId, email);

        // Erster Versuch schlaegt fehl -> attemptCount == 1.
        recordingJavaMailSender.failForRecipients(email);
        campaignMailService.sendToAll(campaignId);
        assertThat(reload(recipientId).getAttemptCount()).isEqualTo(1);

        // Fehlerregel entfernen und erneut versenden -> attemptCount == 2.
        recordingJavaMailSender.reset();
        campaignMailService.sendToAll(campaignId);
        assertThat(reload(recipientId).getAttemptCount()).isEqualTo(2);
    }

    /** Fall 21: Ein einzelner fehlschlagender Empfaenger stoppt die uebrigen nicht (2x SENT, 1x fehlgeschlagen). */
    @Test
    void oneFailingRecipientDoesNotStopOthers() {
        Long campaignId = readyCampaign();
        Long okOneId = addRecipient(campaignId, "ok1@example.invalid");
        Long failId = addRecipient(campaignId, "fail@example.invalid");
        Long okTwoId = addRecipient(campaignId, "ok2@example.invalid");
        recordingJavaMailSender.failForRecipients("fail@example.invalid");

        SendSummary summary = campaignMailService.sendToAll(campaignId);

        assertThat(summary.sent()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(reload(okOneId).getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(reload(okTwoId).getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(reload(failId).getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    /** Fall 22: Der fehlschlagende Empfaenger wird FAILED mit einer nicht-leeren Fehlerkategorie. */
    @Test
    void failingRecipientBecomesFailedWithCategory() {
        Long campaignId = readyCampaign();
        Long failId = addRecipient(campaignId, "fail@example.invalid");
        recordingJavaMailSender.failForRecipients("fail@example.invalid");

        SendSummary summary = campaignMailService.sendToAll(campaignId);

        CampaignRecipient reloaded = reload(failId);
        assertThat(reloaded.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(reloaded.getFailureCategory()).isNotBlank();
        assertThat(summary.failedEmails()).containsExactly("fail@example.invalid");
    }

    /** Fall 23: Ein bereits SENT-Empfaenger wird beim zweiten Versand uebersprungen (keine neue Nachricht). */
    @Test
    void alreadySentRecipientIsSkippedOnSecondSend() {
        Long campaignId = readyCampaign();
        addRecipient(campaignId, "a@example.invalid");

        campaignMailService.sendToAll(campaignId);
        assertThat(recordingJavaMailSender.getSentCount()).isEqualTo(1);

        SendSummary second = campaignMailService.sendToAll(campaignId);

        assertThat(second.skippedAlreadySent()).isEqualTo(1);
        assertThat(second.attempted()).isZero();
        assertThat(second.sent()).isZero();
        // Keine neue Nachricht fuer den bereits versendeten Empfaenger.
        assertThat(recordingJavaMailSender.getSentCount()).isEqualTo(1);
    }

    /** Fall 24: Ein FAILED-Empfaenger wird beim naechsten Versand erneut versucht und wird SENT (attemptCount==2). */
    @Test
    void failedRecipientIsRetriedOnNextSend() {
        Long campaignId = readyCampaign();
        String email = "retry@example.invalid";
        Long recipientId = addRecipient(campaignId, email);

        recordingJavaMailSender.failForRecipients(email);
        campaignMailService.sendToAll(campaignId);
        assertThat(reload(recipientId).getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);

        // Fehlerregel entfernen -> erneuter Versuch gelingt.
        recordingJavaMailSender.reset();
        campaignMailService.sendToAll(campaignId);

        CampaignRecipient reloaded = reload(recipientId);
        assertThat(reloaded.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(reloaded.getAttemptCount()).isEqualTo(2);
    }

    /** Fall 25: Bei deaktiviertem Live-Send wirft sendToAll und es wird nichts (auch kein Fake-Erfolg) versendet. */
    @Test
    void liveSendDisabledThrowsAndSendsNothing() {
        appMailProperties.setLiveSendEnabled(false);
        Long campaignId = readyCampaign();
        addRecipient(campaignId, "a@example.invalid");

        assertThatThrownBy(() -> campaignMailService.sendToAll(campaignId))
                .isInstanceOf(SendNotAllowedException.class);
        assertThat(recordingJavaMailSender.getSentCount()).isZero();
    }

    /** Fall 26: Die Empfaenger-Domain-Allowlist blockiert eine fremde Domain; erlaubte Domain wird versendet. */
    @Test
    void recipientDomainAllowlistBlocksOtherDomain() {
        appMailProperties.setAllowedRecipientDomains(List.of("example.invalid"));
        Long campaignId = readyCampaign();
        Long okId = addRecipient(campaignId, "ok@example.invalid");
        Long blockedId = addRecipient(campaignId, "x@other.invalid");

        SendSummary summary = campaignMailService.sendToAll(campaignId);

        assertThat(summary.sent()).isEqualTo(1);
        assertThat(summary.blockedByRecipientAllowlist()).isEqualTo(1);
        assertThat(summary.blockedEmails()).containsExactly("x@other.invalid");
        assertThat(reload(okId).getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        // Blockierter Empfaenger bleibt unveraendert NOT_SENT.
        assertThat(reload(blockedId).getDeliveryStatus()).isEqualTo(DeliveryStatus.NOT_SENT);
        assertThat(recordingJavaMailSender.getSentCount()).isEqualTo(1);
    }

    /** Fall 27: Ein nicht erlaubter Absender macht die Kampagne nicht versandbereit; sendToAll wirft, nichts wird gesendet. */
    @Test
    void notAllowedSenderBlocksSend() {
        // Absender ausserhalb der Allowlist (diese bleibt training@example.invalid).
        Long campaignId = campaignService
                .create("Boese", null, "Boese", "evil@notallowed.invalid", SUBJECT, BODY).getId();
        addRecipient(campaignId, "a@example.invalid");

        SendReadiness readiness = campaignMailService.checkReadiness(campaignId);
        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.blockers()).contains("Absender ist nicht in der Allowlist erlaubt.");

        assertThatThrownBy(() -> campaignMailService.sendToAll(campaignId))
                .isInstanceOf(SendNotAllowedException.class);
        assertThat(recordingJavaMailSender.getSentCount()).isZero();
    }

    /** Fall 28: Das SMTP-Passwort leakt niemals - nicht in Logs, nicht in der SendSummary. */
    @Test
    void smtpPasswordNeverLeaks() {
        Long campaignId = readyCampaign();
        addRecipient(campaignId, "ok@example.invalid");
        addRecipient(campaignId, "fail@example.invalid");
        recordingJavaMailSender.failForRecipients("fail@example.invalid");

        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        SendSummary summary;
        try {
            summary = campaignMailService.sendToAll(campaignId);
        } finally {
            rootLogger.detachAppender(appender);
            appender.stop();
        }

        // Weder die aufgezeichneten Logmeldungen noch die Zusammenfassung enthalten das Passwort.
        assertThat(appender.list)
                .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains(SECRET));
        assertThat(summary.toString()).doesNotContain(SECRET);
        // Der Versand ist trotz eines Fehlversuchs regulaer durchgelaufen (keine Ausnahme, ein Erfolg).
        assertThat(summary.sent()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
    }
}
