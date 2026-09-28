package de.internal.awareness.mail;

import de.internal.awareness.contact.ContactService;
import de.internal.awareness.file.FileStorageService;
import de.internal.awareness.file.GeneratedFile;
import de.internal.awareness.file.GeneratedFileRepository;
import de.internal.awareness.file.GeneratedFileType;
import de.internal.awareness.file.SafeFileNames;
import de.internal.awareness.recipient.DeliveryStatus;
import de.internal.awareness.support.RecordedMimeMail;
import de.internal.awareness.support.RecordingJavaMailSender;
import de.internal.awareness.support.TestMailConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Versand mit den neuen passiven Anhangstypen (PDF/XLSX/PPTX/TXT/CSV) ueber {@link MailComposerService} -
 * gleicher Aufbau wie {@code MailComposerServiceTest}: reale (isolierte) SQLite-Test-DB, Temp-Datenverzeichnis
 * und aufzeichnender {@link RecordingJavaMailSender} (kein echtes SMTP).
 *
 * <p>Geprueft wird, dass eine Bibliotheksdatei dieser Typen als Anhang mit dem gespeicherten Download-Namen,
 * dem korrekten Basis-Content-Type und byte-identischem Inhalt versendet wird - auch bei aktiviertem
 * Trainingslink (fuer diese Typen findet bewusst KEINE Anhang-Personalisierung statt). Die Bibliotheksdatei
 * wird bewusst ohne die (neuen) Generatoren direkt ueber {@link FileStorageService} und
 * {@link GeneratedFileRepository} angelegt.</p>
 *
 * <p>Hinweis: {@link RecordedMimeMail.Attachment#baseContentType()} entfernt Parameter nach ';' - fuer TXT/CSV
 * wird daher mit {@code text/plain} bzw. {@code text/csv} verglichen, nicht mit
 * {@link GeneratedFileType#contentType()}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
@Import(TestMailConfig.class)
@TestPropertySource(properties = {
        "spring.mail.host=smtp.example.invalid",
        "spring.mail.password=DoNotLeakThisSecret123"
})
class MailComposerPassiveAttachmentTypesTest {

    private static final String SENDER_EMAIL = "training@example.invalid";
    private static final String SENDER_NAME = "IT Security";
    private static final String SUBJECT = "Wichtige Information";
    private static final String BODY = "Hallo, dies ist ein autorisierter interner Test.";
    private static final String TRACKING_BASE_URL = "https://training.example.invalid";

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
    private FileStorageService fileStorageService;

    @Autowired
    private GeneratedFileRepository generatedFileRepository;

    @Autowired
    private de.internal.awareness.config.AppMailProperties appMailProperties;

    @Autowired
    private de.internal.awareness.config.AppTrackingProperties appTrackingProperties;

    @Autowired
    private RecordingJavaMailSender recordingJavaMailSender;

    @Autowired
    private MailBatchRepository mailBatchRepository;

    @Autowired
    private MailDeliveryRepository mailDeliveryRepository;

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

    /** Rohe Test-Bytes je Typ (inkl. aller 256 Bytewerte und UTF-8-Umlauten) - unabhaengig von den Generatoren. */
    private static byte[] sampleContent(GeneratedFileType type) {
        String text = switch (type) {
            case PDF -> "%PDF-1.7\n% passives Trainingsdokument (Testbytes)\n";
            case XLSX, PPTX -> "PK\u0003\u0004 passive Office-Testbytes " + type.name() + "\n";
            case TXT -> "Hinweis zur Schulung - Umlaute: \u00e4\u00f6\u00fc\u00df\n";
            case CSV -> "Name;Abteilung\n\u00c4rger;IT\n";
            default -> throw new IllegalArgumentException("Nur neue Typen: " + type);
        };
        byte[] prefix = text.getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[prefix.length + 256];
        System.arraycopy(prefix, 0, content, 0, prefix.length);
        for (int i = 0; i < 256; i++) {
            content[prefix.length + i] = (byte) i;
        }
        return content;
    }

    /**
     * Legt eine Bibliotheksdatei des Typs an - wie der Dienst es tut (interner UUID-Name ueber
     * {@link FileStorageService}, bereinigter Download-Name, Content-Type aus dem Typ), aber ohne Generator.
     */
    private GeneratedFile libraryFile(GeneratedFileType type, byte[] content) {
        String stored = fileStorageService.store(content, type);
        String download = SafeFileNames.safeDownloadFilename("schulung-unterlage", type);
        return generatedFileRepository.saveAndFlush(new GeneratedFile("Schulungsunterlage " + type.name(), stored,
                download, type, type.contentType(), content.length));
    }

    @ParameterizedTest
    @CsvSource({
            "PDF,  application/pdf",
            "XLSX, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "PPTX, application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "TXT,  text/plain",
            "CSV,  text/csv"
    })
    void sendsPassiveAttachmentWithStoredNameContentTypeAndIdenticalBytes(GeneratedFileType type,
                                                                          String expectedBaseContentType) {
        byte[] content = sampleContent(type);
        GeneratedFile file = libraryFile(type, content);
        Long c = contact("a@example.invalid");

        MailComposeSummary summary = service.send(new MailSendRequest(SUBJECT, BODY, file.getId(), List.of(c), false));

        assertThat(summary.sent()).isEqualTo(1);
        RecordedMimeMail mail = recordingJavaMailSender.getSentMimeMails().get(0);
        assertThat(mail.attachments()).hasSize(1);
        RecordedMimeMail.Attachment att = mail.attachments().get(0);
        assertThat(att.filename()).isEqualTo(file.getDownloadFilename())
                .isEqualTo("schulung-unterlage." + type.extension());
        assertThat(att.baseContentType()).isEqualTo(expectedBaseContentType);
        assertThat(att.bytes()).isEqualTo(content);

        // Historie: Batch verweist auf die Bibliotheksdatei und haelt den Download-Namen fest.
        MailBatch batch = mailBatchRepository.findById(summary.batchId()).orElseThrow();
        assertThat(batch.getAttachmentFilename()).isEqualTo(file.getDownloadFilename());
        assertThat(batch.getGeneratedFile()).isNotNull();
        assertThat(batch.getGeneratedFile().getFileType()).isEqualTo(type);
    }

    @ParameterizedTest
    @CsvSource({
            "PDF,  application/pdf",
            "XLSX, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "PPTX, application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "TXT,  text/plain",
            "CSV,  text/csv"
    })
    void trackingLinkDoesNotPersonalizePassiveAttachmentAndOriginalStaysUnchanged(
            GeneratedFileType type, String expectedBaseContentType) {
        byte[] content = sampleContent(type);
        GeneratedFile file = libraryFile(type, content);
        long filesBefore = generatedFileRepository.count();
        List<Long> ids = List.of(contact("a@example.invalid"), contact("b@example.invalid"));

        MailComposeSummary summary = service.send(new MailSendRequest(SUBJECT, BODY, file.getId(), ids, true));

        assertThat(summary.sent()).isEqualTo(2);
        List<RecordedMimeMail> mails = recordingJavaMailSender.getSentMimeMails();
        assertThat(mails).hasSize(2);
        for (RecordedMimeMail mail : mails) {
            assertThat(mail.attachments()).hasSize(1);
            RecordedMimeMail.Attachment att = mail.attachments().get(0);
            assertThat(att.filename()).isEqualTo(file.getDownloadFilename());
            assertThat(att.baseContentType()).isEqualTo(expectedBaseContentType);
            // Keine Anhang-Personalisierung: jeder Empfaenger erhaelt byte-identisch das Original ...
            assertThat(att.bytes()).isEqualTo(content);
            // ... ohne eingefuegten Trainingslink.
            assertThat(new String(att.bytes(), StandardCharsets.ISO_8859_1))
                    .doesNotContain(TRACKING_BASE_URL).doesNotContain("/t/");
        }

        // Jede Zustellung ist versendet und hat ihre eigene Tracking-Identitaet (nur Hash gespeichert).
        MailBatch batch = mailBatchRepository.findById(summary.batchId()).orElseThrow();
        assertThat(mailDeliveryRepository.findByBatch(batch))
                .hasSize(2)
                .allSatisfy(d -> {
                    assertThat(d.getStatus()).isEqualTo(DeliveryStatus.SENT);
                    assertThat(d.getTrackingTokenHash()).matches("[0-9a-f]{64}");
                })
                .extracting(MailDelivery::getTrackingTokenHash)
                .doesNotHaveDuplicates();

        // Das Original der Bibliothek bleibt unveraendert und es wurde keine Datei zusaetzlich gespeichert.
        assertThat(fileStorageService.read(file.getStoredFilename())).isEqualTo(content);
        assertThat(generatedFileRepository.count()).isEqualTo(filesBefore);
    }

    @ParameterizedTest
    @CsvSource({
            "TXT, text/plain",
            "CSV, text/csv"
    })
    void textAttachmentsDeclareUtf8Charset(GeneratedFileType type, String expectedBaseContentType) {
        byte[] content = sampleContent(type);
        GeneratedFile file = libraryFile(type, content);
        Long c = contact("a@example.invalid");

        service.send(new MailSendRequest(SUBJECT, BODY, file.getId(), List.of(c), false));

        RecordedMimeMail.Attachment att = recordingJavaMailSender.getSentMimeMails().get(0).attachments().get(0);
        assertThat(att.baseContentType()).isEqualTo(expectedBaseContentType);
        // Der gespeicherte Content-Type (inkl. charset=UTF-8) wird unveraendert in den MIME-Teil uebernommen.
        assertThat(att.contentType().toLowerCase(Locale.ROOT).replace(" ", "")).contains("charset=utf-8");
        assertThat(att.bytes()).isEqualTo(content);
    }
}
