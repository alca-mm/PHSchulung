package de.internal.awareness.web.api.dto;

import java.util.List;

/**
 * JSON-Antwort des Dashboard-Endpoints ({@code GET /api/tracking}): globale Kennzahlen, die gefilterte und
 * sortierte Zeilenliste, das Batch-Dropdown sowie der (normalisierte) angewandte Filter.
 *
 * @param summary globale Kennzahlen (unabhaengig vom Filter)
 * @param rows    gefilterte und sortierte Zeilen
 * @param batches Eintraege fuer das Batch-Auswahlfeld (nur Batches mit getrackter Zustellung, neueste zuerst)
 * @param filter  der normalisierte, angewandte Filter (fuer die Formular-Wiederanzeige)
 */
public record DashboardResponse(DashboardSummaryDto summary, List<DashboardRowDto> rows,
                                List<BatchOptionDto> batches, DashboardFilterDto filter) {
}
