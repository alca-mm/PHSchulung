package de.internal.awareness.tracking;

import de.internal.awareness.recipient.CampaignRecipient;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Ein einzelnes registriertes Awareness-Ereignis (z. B. Klick auf einen Trainingslink).
 * Mehrere Ereignisse desselben Empfaengers sind erlaubt, damit spaeter erster Klick und
 * Gesamtanzahl ausgewertet werden koennen. Es werden bewusst KEINE IP-Adressen,
 * User-Agents, Fingerprints oder sonstige unnoetige personenbezogene Telemetrie gespeichert.
 */
@Entity
@Table(name = "tracking_event")
public class TrackingEvent {

    // Siehe Campaign#id: INTEGER-Mapping fuer SQLite-IDENTITY + Hibernate-Validierung.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JdbcTypeCode(SqlTypes.INTEGER)
    private Long id;

    @NotNull
    @ManyToOne(optional = false)
    @JoinColumn(
            name = "recipient_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_event_recipient")
    )
    private CampaignRecipient recipient;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private TrackingEventType type;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** Fuer JPA. */
    protected TrackingEvent() {
    }

    public TrackingEvent(CampaignRecipient recipient, TrackingEventType type) {
        this.recipient = recipient;
        this.type = type;
    }

    public TrackingEvent(CampaignRecipient recipient, TrackingEventType type, Instant occurredAt) {
        this.recipient = recipient;
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

    public CampaignRecipient getRecipient() {
        return recipient;
    }

    public TrackingEventType getType() {
        return type;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
