package de.internal.awareness.recipient;

/**
 * Versandstatus einer Trainings-Mail an einen einzelnen {@link CampaignRecipient}.
 *
 * <ul>
 *   <li>{@link #NOT_SENT} - noch nicht (erfolgreich) versendet (Ausgangszustand).</li>
 *   <li>{@link #SENT} - erfolgreich an den SMTP-Server uebergeben.</li>
 *   <li>{@link #FAILED} - Uebergabe fehlgeschlagen; darf erneut versucht werden.</li>
 * </ul>
 *
 * <p>Die Werte muessen exakt der CHECK-Constraint der Spalte {@code delivery_status} entsprechen
 * (Flyway V3); ein neuer Wert erfordert eine neue Migration.</p>
 */
public enum DeliveryStatus {
    NOT_SENT,
    SENT,
    FAILED
}
