package de.internal.awareness.campaign;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Eine Awareness-Trainingskampagne. Zeitpunkte werden UTC-basiert als {@link Instant}
 * gespeichert; die Anzeige in Europe/Berlin ist Aufgabe der Praesentationsschicht.
 */
@Entity
@Table(name = "campaign")
public class Campaign {

    // SQLite/Hibernate: IDENTITY-Ids werden als INTEGER (rowid) erzeugt; ohne diese Angabe
    // erwartet die Hibernate-Validierung fuer Long faelschlich BIGINT. Feld bleibt Long.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JdbcTypeCode(SqlTypes.INTEGER)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    /** Optionaler interner Beschreibungstext (nicht empfaengerseitig sichtbar). */
    @Column(name = "description", length = 2000)
    private String description;

    @NotBlank
    @Column(name = "email_subject", nullable = false)
    private String emailSubject;

    /**
     * Sichtbare Absenderadresse (KEIN Secret). Bewusst NULLABLE auf DB-Ebene, damit Alt-Kampagnen ohne
     * Absender gueltig bleiben; die Pflicht (gueltige Adresse) wird am Formular und vor dem Versand geprueft.
     */
    @Column(name = "sender_email")
    private String senderEmail;

    /** Optionaler Anzeigename des Absenders (z. B. "IT Security"). */
    @Column(name = "sender_name")
    private String senderName;

    /**
     * Reiner Text der Trainings-Mail. Wird als Plaintext versendet und NIE als Template/Code
     * interpretiert. Nullable analog zu {@link #senderEmail} (Pflicht am Formular/vor dem Versand).
     */
    @Column(name = "email_body", length = 10000)
    private String emailBody;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Fuer JPA. */
    protected Campaign() {
    }

    public Campaign(String name, String emailSubject) {
        this.name = name;
        this.emailSubject = emailSubject;
        this.status = CampaignStatus.DRAFT;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEmailSubject() {
        return emailSubject;
    }

    public void setEmailSubject(String emailSubject) {
        this.emailSubject = emailSubject;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public void setSenderEmail(String senderEmail) {
        this.senderEmail = senderEmail;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getEmailBody() {
        return emailBody;
    }

    public void setEmailBody(String emailBody) {
        this.emailBody = emailBody;
    }

    public CampaignStatus getStatus() {
        return status;
    }

    public void setStatus(CampaignStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
