package de.internal.awareness.web.api.dto;

/**
 * JSON-Uebertragungsobjekt fuer die globalen Kennzahlen (KPIs) des Tracking-Dashboards. Die Kennzahlen sind
 * stets GLOBAL ueber alle getrackten Zustellungen und damit unabhaengig vom gesetzten Filter.
 *
 * <p>Bewusst explizites, entkoppeltes DTO (kein Durchreichen einer JPA-Entity oder eines Service-Records): Es
 * werden ausschliesslich nicht sensible Aggregatzahlen ausgegeben - niemals Token, Token-Hashes oder sonstige
 * Geheimnisse.</p>
 *
 * @param trackedDeliveries   Anzahl aller getrackten Zustellungen (je Zustellung ein Empfaenger-Eintrag)
 * @param sentDeliveries      Anzahl getrackter Zustellungen mit Status {@code SENT}
 * @param reactedDeliveries   Anzahl getrackter Zustellungen mit mindestens einer Aktion
 * @param notReactedDeliveries Anzahl getrackter Zustellungen ohne Aktion
 * @param totalActions        Gesamtzahl aller Aktionen (Klickereignisse)
 * @param reactionRate        Anteil reagierender Empfaenger (0.0..1.0)
 * @param averageActions      durchschnittliche Aktionen je reagierendem Empfaenger
 */
public record DashboardSummaryDto(long trackedDeliveries, long sentDeliveries, long reactedDeliveries,
                                  long notReactedDeliveries, long totalActions, double reactionRate,
                                  double averageActions) {
}
