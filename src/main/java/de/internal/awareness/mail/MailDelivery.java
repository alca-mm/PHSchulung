package de.internal.awareness.mail;

import de.internal.awareness.contact.Contact;
import de.internal.awareness.recipient.DeliveryStatus;
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
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Die Zustellung eines {@link MailBatch} an genau einen {@link Contact}: Status, Versuchszaehler,
 * Sendezeitpunkt und - bei Fehlern - eine kurze, sanitizte Fehlerkategorie.
 *
 * <p>Diese Entity ist die per-Empfaenger-Granularitaet des globalen Versands und traegt die
 * empfaengerbezogene, nicht erratbare Tracking-Identitaet ({@code tracking_token_hash}). Gespeichert wird
 * ausschliesslich der SHA-256-Hash eines kryptografisch starken Zufallstokens (siehe {@code TrackingTokens});
 * der Klartext-Token wird NICHT persistiert und NICHT geloggt. Ein spaeter eingehender Token wird zum Lookup
 * gehasht und ueber den eindeutigen Hash gefunden. Die Spalte ist auf DB-Ebene NULLBAR, damit etwaige
 * Alt-Deliveries (vor Einfuehrung des Trackings) gueltig bleiben; neue Deliveries erhalten immer einen Hash.</p>
 *
 * <p>Ein Klick auf den sichtbaren Trainingslink wird als {@code MailTrackingEvent} (Typ LINK_CLICK) mit
 * FK auf diese Zustellung registriert. Das blosse Oeffnen der Datei erzeugt KEIN Event (kein Open-/Pixel-/
 * Remote-Tracking) - nur die bewusste Benutzeraktion auf den Link.</p>
 *
 * <p>Datenschutz: {@code failureCategory} enthaelt nur eine kurze Kategorie (z. B. {@code AUTH},
 * {@code SEND}, {@code BLOCKED}) - niemals vollstaendige SMTP-Serverantworten oder sensible Details.</p>
 */
@Entity
@Table(
        name = "mail_delivery",
        // Indizes auf die FK-Spalten (Lookups je Batch/Kontakt + FK-Pruefung beim Loeschen, Flyway V7) sowie
        // der Unique-Index auf den Tracking-Hash (Flyway V8). Der Name muss zum @Index passen
        // (Hibernate unique_key_validation=NAMED).
        indexes = {
                @Index(name = "ix_delivery_batch", columnList = "batch_id"),
                @Index(name = "ix_delivery_contact", columnList = "contact_id"),
                @Index(name = "uk_mail_delivery_tracking_token_hash", columnList = "tracking_token_hash", unique = true)
        }
)
public class MailDelivery {

    // Siehe Campaign#id: INTEGER-Mapping fuer SQLite-IDENTITY (rowid-Alias) + Hibernate-Validierung.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JdbcTypeCode(SqlTypes.INTEGER)
    private Long id;

    @NotNull
    @ManyToOne(optional = false)
    @JoinColumn(
            name = "batch_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_delivery_batch")
    )
    private MailBatch batch;

    @NotNull
    @ManyToOne(optional = false)
    @JoinColumn(
            name = "contact_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_delivery_contact")
    )
    private Contact contact;

    /** Versandstatus dieser Zustellung. Muss der CHECK-Constraint der Spalte {@code status} entsprechen (Flyway V7). */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DeliveryStatus status = DeliveryStatus.NOT_SENT;

    /** Anzahl der Versandversuche. */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    /** Zeitpunkt des erfolgreichen Versands (UTC). Null, solange nicht erfolgreich versendet. */
    @Column(name = "sent_at")
    private Instant sentAt;

    /** Kurze, sanitizte Fehlerkategorie des letzten Fehlversuchs. Nie vollstaendige Serverantworten. */
    @Column(name = "failure_category", length = 64)
    private String failureCategory;

    /**
     * Sichere Repraesentation der empfaengerbezogenen Tracking-Identitaet: SHA-256-Hash (Hex) des
     * Zufallstokens. DB-nullbar (Alt-Daten), fuer neue Deliveries immer gesetzt; global eindeutig
     * (Unique-Index uk_mail_delivery_tracking_token_hash, Flyway V8). Der Klartext-Token wird NIE gespeichert.
     */
    @Column(name = "tracking_token_hash", length = 64)
    private String trackingTokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Fuer JPA. */
    protected MailDelivery() {
    }

    public MailDelivery(MailBatch batch, Contact contact, String trackingTokenHash) {
        this.batch = batch;
        this.contact = contact;
        this.trackingTokenHash = trackingTokenHash;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public MailBatch getBatch() {
        return batch;
    }

    public Contact getContact() {
        return contact;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(DeliveryStatus status) {
        this.status = status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getFailureCategory() {
        return failureCategory;
    }

    /** Der gespeicherte SHA-256-Hash der Tracking-Identitaet (kein Klartext-Token). Kann fuer Alt-Daten null sein. */
    public String getTrackingTokenHash() {
        return trackingTokenHash;
    }

    /** Registriert einen beginnenden Versandversuch: erhoeht den Zaehler. */
    public void recordAttempt() {
        this.attemptCount++;
    }

    /** Markiert die Zustellung als erfolgreich: Status {@link DeliveryStatus#SENT}, {@code sentAt} gesetzt. */
    public void recordSent(Instant when) {
        this.status = DeliveryStatus.SENT;
        this.sentAt = when;
        this.failureCategory = null;
    }

    /**
     * Markiert die Zustellung als fehlgeschlagen: Status {@link DeliveryStatus#FAILED} und kurze, sanitizte
     * Fehlerkategorie. Speichert bewusst keine vollstaendigen Serverantworten.
     */
    public void recordFailure(String category) {
        this.status = DeliveryStatus.FAILED;
        this.failureCategory = category;
    }

    /**
     * Markiert die Zustellung als durch die Empfaenger-Allowlist blockiert: Status bleibt
     * {@link DeliveryStatus#NOT_SENT} (es wurde bewusst kein SMTP-Versuch unternommen), festgehalten wird nur
     * die kurze Kategorie (z. B. {@code BLOCKED}). So bleibt in der Historie nachvollziehbar, dass der
     * Empfaenger nicht beliefert wurde, ohne dies faelschlich als Sendefehler zu werten.
     */
    public void recordBlocked(String category) {
        this.status = DeliveryStatus.NOT_SENT;
        this.failureCategory = category;
    }
}
