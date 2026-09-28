package de.internal.awareness.contact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Ein dauerhaft gespeicherter, kampagnenunabhaengiger Empfaenger-Kontakt (globale, wiederverwendbare
 * Empfaengerliste fuer den E-Mail-Composer).
 *
 * <p>Abgrenzung zu {@code CampaignRecipient}: Ein {@code Contact} gehoert KEINER Kampagne, sondern der
 * globalen Liste; er kann in beliebig vielen Versandvorgaengen (MailBatch) verwendet werden. Die
 * E-Mail-Adresse ist global eindeutig (case-insensitive, DB-Unique-Index mit {@code COLLATE NOCASE},
 * Flyway V6).</p>
 */
@Entity
@Table(
        name = "contact",
        // Unique-Index (email COLLATE NOCASE) wird von Flyway (V6) angelegt; Hibernate validiert den
        // benannten Unique-Key (unique_key_validation=NAMED). Name nicht aendern.
        indexes = @Index(name = "uk_contact_email", columnList = "email", unique = true)
)
public class Contact {

    // Siehe Campaign#id: INTEGER-Mapping fuer SQLite-IDENTITY (rowid-Alias) + Hibernate-Validierung.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JdbcTypeCode(SqlTypes.INTEGER)
    private Long id;

    @NotBlank
    @Email
    @Column(nullable = false)
    private String email;

    /** Optionaler Anzeigename. */
    @Column(name = "display_name")
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Fuer JPA. */
    protected Contact() {
    }

    public Contact(String email, String displayName) {
        this.email = email;
        this.displayName = displayName;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
