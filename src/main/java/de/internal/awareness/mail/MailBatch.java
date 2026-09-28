package de.internal.awareness.mail;

import de.internal.awareness.file.GeneratedFile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Ein Versandvorgang des globalen E-Mail-Composers: eine Nachricht (Betreff/Text/optionaler Anhang), die
 * als individuelle Einzelmails an mehrere ausgewaehlte {@link de.internal.awareness.contact.Contact}
 * versendet wurde. Die konkreten Zustellungen je Kontakt haelt {@link MailDelivery}.
 *
 * <p>Datenschutz/Sicherheit: enthaelt bewusst KEINE SMTP-Zugangsdaten, keine Tracking-Tokens und keine
 * vollstaendigen SMTP-Serverantworten. Die Absenderadresse ist die (nicht sensible) konfigurierte Adresse.
 * Der optionale Anhang wird als Referenz auf einen {@link GeneratedFile} sowie mit einer denormalisierten
 * Kopie des Downloadnamens festgehalten (damit die Historie lesbar bleibt).</p>
 */
@Entity
@Table(
        name = "mail_batch",
        // Index auf die FK-Spalte (Lookups je Datei + FK-Pruefung beim Loeschen einer Datei). Flyway V7.
        indexes = @Index(name = "ix_batch_generated_file", columnList = "generated_file_id")
)
public class MailBatch {

    // Siehe Campaign#id: INTEGER-Mapping fuer SQLite-IDENTITY (rowid-Alias) + Hibernate-Validierung.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JdbcTypeCode(SqlTypes.INTEGER)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String subject;

    /** Kopie des versendeten Plaintext-Textes (begrenzte Laenge, analog zu Campaign#emailBody). */
    @NotBlank
    @Column(name = "body", nullable = false, length = 10000)
    private String body;

    /** Sichtbare Absenderadresse dieses Versands (KEIN Secret). */
    @NotBlank
    @Column(name = "sender_email", nullable = false)
    private String senderEmail;

    /** Optionaler Absender-Anzeigename. */
    @Column(name = "sender_name")
    private String senderName;

    /**
     * Optionaler Anhang: Referenz auf die verwendete Datei. NULLABLE (Versand ohne Anhang). ON DELETE
     * RESTRICT (Flyway V7): eine verwendete Datei kann nicht geloescht werden, solange Batches sie referenzieren.
     */
    @ManyToOne(optional = true)
    @JoinColumn(
            name = "generated_file_id",
            foreignKey = @ForeignKey(name = "fk_batch_generated_file")
    )
    private GeneratedFile generatedFile;

    /** Denormalisierte Kopie des Anhang-Downloadnamens fuer die Historie (NULL, wenn kein Anhang). */
    @Column(name = "attachment_filename")
    private String attachmentFilename;

    /** Anzahl der fuer diesen Versand ausgewaehlten Empfaenger. */
    @PositiveOrZero
    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Fuer JPA. */
    protected MailBatch() {
    }

    public MailBatch(String subject, String body, String senderEmail, String senderName,
                     GeneratedFile generatedFile, String attachmentFilename, int recipientCount) {
        this.subject = subject;
        this.body = body;
        this.senderEmail = senderEmail;
        this.senderName = senderName;
        this.generatedFile = generatedFile;
        this.attachmentFilename = attachmentFilename;
        this.recipientCount = recipientCount;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public String getSenderName() {
        return senderName;
    }

    public GeneratedFile getGeneratedFile() {
        return generatedFile;
    }

    public String getAttachmentFilename() {
        return attachmentFilename;
    }

    public int getRecipientCount() {
        return recipientCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
