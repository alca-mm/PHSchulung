package de.internal.awareness.recipient;

/**
 * Ergebnis der Empfaenger-Anlage: der gespeicherte {@link CampaignRecipient} und der EINMALIG
 * erzeugte Klartext-Token.
 *
 * <p>Sicherheit/Datenschutz:</p>
 * <ul>
 *   <li>Dies ist KEINE Entity und wird NICHT persistiert; der Klartext-Token existiert nur in diesem
 *       fluechtigen Objekt und wird nach Verwendung verworfen.</li>
 *   <li>{@link #toString()} ist redaktiert und gibt den Klartext-Token NIE aus, damit er nicht
 *       versehentlich ueber Logging oder String-Konkatenation leakt.</li>
 *   <li>Der Klartext-Token ({@link #plaintextToken()}) bleibt programmgesteuert abrufbar, ist aber
 *       ausdruecklich nicht zum Loggen oder dauerhaften Speichern bestimmt.</li>
 * </ul>
 */
public record RecipientRegistration(CampaignRecipient recipient, String plaintextToken) {

    /** Redaktierte Darstellung: enthaelt den Klartext-Token NICHT. */
    @Override
    public String toString() {
        Long id = recipient == null ? null : recipient.getId();
        return "RecipientRegistration[recipientId=" + id + ", plaintextToken=***redacted***]";
    }
}
