package de.internal.awareness.mail;

import de.internal.awareness.contact.Contact;
import de.internal.awareness.contact.ContactRepository;
import de.internal.awareness.file.GeneratedFile;
import de.internal.awareness.file.GeneratedFileRepository;
import de.internal.awareness.file.GeneratedFileType;
import de.internal.awareness.recipient.DeliveryStatus;
import de.internal.awareness.support.SqliteFlywayJpaTest;
import de.internal.awareness.tracking.TrackingTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistenz der Versandhistorie: {@link MailBatch} (mit und ohne Anhang) und {@link MailDelivery} je
 * Kontakt inkl. Statuspflege, Zaehlungen je Status und der Zuordnung Batch -&gt; Zustellungen.
 */
@SqliteFlywayJpaTest
class MailHistoryPersistenceTest {

    @Autowired
    private MailBatchRepository batchRepository;

    @Autowired
    private MailDeliveryRepository deliveryRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private GeneratedFileRepository fileRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Contact newContact(String email) {
        return contactRepository.saveAndFlush(new Contact(email, "Name " + email));
    }

    /** Baut eine Zustellung mit eigener (zufaelliger) Tracking-Identitaet - nur der Hash wird gespeichert. */
    private static MailDelivery delivery(MailBatch batch, Contact contact) {
        return new MailDelivery(batch, contact, TrackingTokens.generate().tokenHash());
    }

    @Test
    void savesBatchWithoutAttachmentAndReloads() {
        MailBatch batch = batchRepository.saveAndFlush(new MailBatch(
                "Betreff", "Hallo", "training@example.invalid", "IT Security", null, null, 2));
        entityManager.clear();

        MailBatch reloaded = batchRepository.findById(batch.getId()).orElseThrow();
        assertThat(reloaded.getSubject()).isEqualTo("Betreff");
        assertThat(reloaded.getBody()).isEqualTo("Hallo");
        assertThat(reloaded.getSenderEmail()).isEqualTo("training@example.invalid");
        assertThat(reloaded.getSenderName()).isEqualTo("IT Security");
        assertThat(reloaded.getGeneratedFile()).isNull();
        assertThat(reloaded.getAttachmentFilename()).isNull();
        assertThat(reloaded.getRecipientCount()).isEqualTo(2);
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void savesBatchWithAttachmentReference() {
        GeneratedFile file = fileRepository.saveAndFlush(new GeneratedFile("Rechnung",
                "abc.docx", "rechnung.docx", GeneratedFileType.DOCX, GeneratedFileType.DOCX.contentType(), 10L));
        MailBatch batch = batchRepository.saveAndFlush(new MailBatch(
                "Betreff", "Hallo", "training@example.invalid", null, file, "rechnung.docx", 1));
        entityManager.clear();

        MailBatch reloaded = batchRepository.findById(batch.getId()).orElseThrow();
        assertThat(reloaded.getGeneratedFile()).isNotNull();
        assertThat(reloaded.getGeneratedFile().getId()).isEqualTo(file.getId());
        assertThat(reloaded.getAttachmentFilename()).isEqualTo("rechnung.docx");
    }

    @Test
    void savesDeliveriesWithStatusAndCountsByStatus() {
        MailBatch batch = batchRepository.saveAndFlush(new MailBatch(
                "Betreff", "Hallo", "training@example.invalid", null, null, null, 3));
        Contact a = newContact("a@example.invalid");
        Contact b = newContact("b@example.invalid");
        Contact c = newContact("c@example.invalid");

        MailDelivery d1 = delivery(batch, a);
        d1.recordAttempt();
        d1.recordSent(Instant.now());
        MailDelivery d2 = delivery(batch, b);
        d2.recordAttempt();
        d2.recordSent(Instant.now());
        MailDelivery d3 = delivery(batch, c);
        d3.recordAttempt();
        d3.recordFailure("SEND");
        deliveryRepository.saveAll(List.of(d1, d2, d3));
        deliveryRepository.flush();
        entityManager.clear();

        MailBatch reloadedBatch = batchRepository.findById(batch.getId()).orElseThrow();
        assertThat(deliveryRepository.findByBatch(reloadedBatch)).hasSize(3);
        assertThat(deliveryRepository.countByBatchAndStatus(reloadedBatch, DeliveryStatus.SENT)).isEqualTo(2L);
        assertThat(deliveryRepository.countByBatchAndStatus(reloadedBatch, DeliveryStatus.FAILED)).isEqualTo(1L);
    }

    @Test
    void deliveryRecordsSentAtAndFailureCategory() {
        MailBatch batch = batchRepository.saveAndFlush(new MailBatch(
                "Betreff", "Hallo", "training@example.invalid", null, null, null, 2));
        MailDelivery ok = delivery(batch, newContact("ok@example.invalid"));
        ok.recordAttempt();
        ok.recordSent(Instant.now());
        MailDelivery bad = delivery(batch, newContact("bad@example.invalid"));
        bad.recordAttempt();
        bad.recordFailure("SEND");
        deliveryRepository.saveAll(List.of(ok, bad));
        deliveryRepository.flush();
        Long okId = ok.getId();
        Long badId = bad.getId();
        entityManager.clear();

        MailDelivery reloadedOk = deliveryRepository.findById(okId).orElseThrow();
        assertThat(reloadedOk.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(reloadedOk.getSentAt()).isNotNull();
        assertThat(reloadedOk.getFailureCategory()).isNull();
        assertThat(reloadedOk.getAttemptCount()).isEqualTo(1);

        MailDelivery reloadedBad = deliveryRepository.findById(badId).orElseThrow();
        assertThat(reloadedBad.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(reloadedBad.getSentAt()).isNull();
        assertThat(reloadedBad.getFailureCategory()).isEqualTo("SEND");
    }
}
