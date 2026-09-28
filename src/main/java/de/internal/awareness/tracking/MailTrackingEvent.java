package de.internal.awareness.tracking;

import de.internal.awareness.mail.MailDelivery;
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
 * Ein registriertes Awareness-Ereignis fuer den globalen Mail-Composer, bezogen auf eine
 * {@link MailDelivery} (also auf genau einen Empfaenger eines {@code MailBatch}).
 *
 * <p>Bewusst getrennt von {@code TrackingEvent} (das an {@code CampaignRecipient} gekoppelt ist): so bleibt
 * die bestehende Kampagnen-Tracking-Struktur voellig unveraendert (keine riskante Tabellen-Umstellung), und
 * das Composer-Tracking haengt sauber an {@link MailDelivery}. Der {@link TrackingEventType} wird jedoch
 * wiederverwendet (keine zweite Enum).</p>
 *
 * <p>Mehrere Ereignisse pro Zustellung sind ausdruecklich erlaubt (Mehrfachklick zaehlt mehrfach); es gibt
 * KEINE Unique-Beschraenkung auf einen Klick je Empfaenger. Es werden bewusst KEINE IP-Adressen,
 * User-Agents, Fingerprints, Geodaten oder sonstige Telemetrie gespeichert - nur die Zuordnung zur
 * Zustellung, der Ereignistyp und der Zeitpunkt.</p>
 */
@Entity
@Table(
        name = "mail_tracking_event",
        // Index auf die FK-Spalte (Lookups je Zustellung + FK-Pruefung beim Loeschen). Flyway V9.
        indexes = @Index(name = "ix_mail_tracking_event_delivery", columnList = "delivery_id")
)
public class MailTrackingEvent {

    // Siehe Campaign#id: INTEGER-Mapping fuer SQLite-IDENTITY (rowid-Alias) + Hibernate-Validierung.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JdbcTypeCode(SqlTypes.INTEGER)
    private Long id;

    @NotNull
    @ManyToOne(optional = false)
    @JoinColumn(
            name = "delivery_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_mail_tracking_event_delivery")
    )
    private MailDelivery delivery;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private TrackingEventType type;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** Fuer JPA. */
    protected MailTrackingEvent() {
    }

    public MailTrackingEvent(MailDelivery delivery, TrackingEventType type) {
        this.delivery = delivery;
        this.type = type;
    }

    public MailTrackingEvent(MailDelivery delivery, TrackingEventType type, Instant occurredAt) {
        this.delivery = delivery;
        this.type = type;
        this.occurredAt = occurredAt;
    }

    @PrePersist
    void onCreate() {
        if (this.occurredAt == null) {
            this.occurredAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public MailDelivery getDelivery() {
        return delivery;
    }

    public TrackingEventType getType() {
        return type;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
