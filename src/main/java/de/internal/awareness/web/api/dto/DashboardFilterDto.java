package de.internal.awareness.web.api.dto;

/**
 * JSON-Uebertragungsobjekt, das den (normalisierten) angewandten Filter zuruueckspiegelt, damit das Frontend
 * seine Eingabefelder exakt wieder anzeigen kann.
 *
 * <p>Alle Werte sind bereits normalisiert: leere/blanke Freitexte werden zu {@code null}; ungueltige Status-/
 * Datumsangaben werden zu {@code null}; {@code reacted}/{@code sort}/{@code dir} tragen ihre Standardwerte
 * ({@code ALL}/{@code LAST_CLICK}/{@code DESC}), falls nichts Gueltiges uebergeben wurde. Die Zeitraumgrenzen
 * werden als reine Datumszeichenketten (yyyy-MM-dd) zurueckgegeben - genau in der Form, die der Benutzer
 * uebergeben hat (bzw. {@code null}, falls ungueltig).</p>
 *
 * @param query    Freitextfilter (Name oder E-Mail), {@code null} = kein Freitextfilter
 * @param batchId  Einschraenkung auf einen Versandvorgang, {@code null} = alle
 * @param fileName Teilstring auf den Anhang-Downloadnamen, {@code null} = keine Einschraenkung
 * @param status   Versandstatus als Name oder {@code null} (kein Statusfilter)
 * @param reacted  Reaktionsfilter als Name ({@code ALL}/{@code REACTED}/{@code NOT_REACTED})
 * @param from     inklusive untere Datumsgrenze (yyyy-MM-dd) oder {@code null}
 * @param to       inklusive obere Datumsgrenze (yyyy-MM-dd) oder {@code null}
 * @param sort     Sortierschluessel als Name ({@code SENT_AT}/{@code FIRST_CLICK}/{@code LAST_CLICK}/{@code ACTIONS})
 * @param dir      Sortierrichtung als Name ({@code ASC}/{@code DESC})
 */
public record DashboardFilterDto(String query, Long batchId, String fileName, String status, String reacted,
                                 String from, String to, String sort, String dir) {
}
