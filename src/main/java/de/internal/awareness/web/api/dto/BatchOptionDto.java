package de.internal.awareness.web.api.dto;

/**
 * JSON-Uebertragungsobjekt fuer einen Eintrag des Batch-Auswahlfeldes (Dropdown) des Tracking-Dashboards.
 *
 * @param id    Id des Versandvorgangs
 * @param label Anzeigebezeichnung, z. B. {@code "#12 - Rechnung September (2026-09-28)"}
 */
public record BatchOptionDto(Long id, String label) {
}
