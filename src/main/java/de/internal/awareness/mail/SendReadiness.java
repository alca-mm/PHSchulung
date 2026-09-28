package de.internal.awareness.mail;

import java.util.List;

/**
 * Ergebnis der Vorab-Pruefung, ob fuer eine Kampagne echt versendet werden darf.
 *
 * @param ready           {@code true}, wenn keine Vorbedingung verletzt ist ({@code blockers} leer)
 * @param blockers        menschenlesbare (deutsche) Gruende, warum (noch) nicht versendet werden darf
 * @param totalRecipients Gesamtzahl der Empfaenger der Kampagne
 * @param sendableCount   Anzahl der versendbaren Empfaenger (NOT_SENT + FAILED)
 */
public record SendReadiness(boolean ready, List<String> blockers, long totalRecipients, long sendableCount) {
}
