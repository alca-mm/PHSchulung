package de.internal.awareness.recipient;

import de.internal.awareness.campaign.Campaign;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Ein konkreter Empfaenger innerhalb einer Kampagne.
 *
 * <p>Die oeffentliche Tracking-Kennung wird bewusst NICHT als fortlaufende DB-ID modelliert.
 * Gespeichert wird ausschliesslich der SHA-256-Hash eines kryptografisch starken Zufallstokens
 * ({@code tracking_token_hash}). Der Klartext-Token wird nicht dauerhaft persistiert; ein spaeter
 * eingehender Token wird zum Lookup gehasht und ueber den eindeutigen Hash gefunden. Tokenwerte
 * duerfen niemals vollstaendig geloggt werden.</p>
 */
@Entity
@Table(
        name = "campaign_recipient",
        // Unique-Indizes werden ausschliesslich von Flyway per CREATE UNIQUE INDEX angelegt
        // (db/migration, ddl-auto=validate). Hibernate erzeugt aus diesen Annotationen kein DDL, prueft
        // die benannten Unique-Keys aber beim Start (unique_key_validation=NAMED) -> Namen nicht aendern.
        indexes = {
                // Tracking-Identitaet (SHA-256-Hash) global eindeutig (Flyway V1).
                @Index(
                        name = "uk_recipient_tracking_token_hash",
                        columnList = "tracking_token_hash",
                        unique = true
                ),
                // Eine E-Mail-Adresse je Kampagne eindeutig - erlaubt aber dieselbe Adresse in
                // verschiedenen Kampagnen (Flyway V4).
                @Index(
                        name = "uk_recipient_campaign_email",
                        columnList = "campaign_id, email",
                        unique = true
                )
        }
)
public class CampaignRecipient {

    // Siehe Campaign#id: INTEGER-Mapping fuer SQLite-IDENTITY + Hibernate-Validierung.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JdbcTypeCode(SqlTypes.INTEGER)
    private Long id;

    @NotNull
    @ManyToOne(optional = false)
    @JoinColumn(
            name = "campaign_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_recipient_campaign")
    )
    private Campaign campaign;

    @NotBlank
    @Email
    @Column(nullable = false)
    private String email;

    /** Optionaler Anzeigename. */
    @Column(name = "display_name")
    private String displayName;

    /** Sichere Repraesentation der Tracking-Identitaet: SHA-256-Hash (Hex) des Zufallstokens. */
    @NotBlank
    @Column(name = "tracking_token_hash", nullable = false, length = 64)
    private String trackingTokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Versandstatus dieser Trainings-Mail. Startet auf {@link DeliveryStatus#NOT_SENT}. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 32)
    private DeliveryStatus deliveryStatus = DeliveryStatus.NOT_SENT;

    /** Anzahl der Versandversuche (erfolgreiche und fehlgeschlagene). */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    /** Zeitpunkt des letzten Versandversuchs (UTC). Null, solange noch nichts versucht wurde. */
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /** Zeitpunkt des erfolgreichen Versands (UTC). Null, solange nicht erfolgreich versendet. */
    @Column(name = "sent_at")
    private Instant sentAt;

    /**
     * Kurze, sanitizte Fehlerkategorie des letzten Fehlversuchs (z. B. {@code AUTH}, {@code CONNECT}).
     * Enthaelt bewusst NIE vollstaendige SMTP-Serverantworten oder sensible Details.
     */
    @Column(name = "failure_category", length = 64)
    private String failureCategory;

    /** Fuer JPA. */
    protected CampaignRecipient() {
    }

    public CampaignRecipient(Campaign campaign, String email, String trackingTokenHash) {
        this.campaign = campaign;
        this.email = email;
        this.trackingTokenHash = trackingTokenHash;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Campaign getCampaign() {
        return campaign;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getTrackingTokenHash() {
        return trackingTokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public DeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public void setDeliveryStatus(DeliveryStatus deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public String getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(String failureCategory) {
        this.failureCategory = failureCategory;
    }

    /** Registriert einen beginnenden Versandversuch: erhoeht den Zaehler und setzt den Zeitpunkt. */
    public void recordAttempt(Instant when) {
        this.attemptCount++;
        this.lastAttemptAt = when;
    }

    /** Markiert den Versand als erfolgreich: Status {@link DeliveryStatus#SENT}, {@code sentAt} gesetzt. */
    public void recordSent(Instant when) {
        this.deliveryStatus = DeliveryStatus.SENT;
        this.sentAt = when;
        this.failureCategory = null;
    }

    /**
     * Markiert den Versuch als fehlgeschlagen: Status {@link DeliveryStatus#FAILED} und kurze, sanitizte
     * Fehlerkategorie. Speichert bewusst keine vollstaendigen Serverantworten.
     */
    public void recordFailure(String category) {
        this.deliveryStatus = DeliveryStatus.FAILED;
        this.failureCategory = category;
    }
}
