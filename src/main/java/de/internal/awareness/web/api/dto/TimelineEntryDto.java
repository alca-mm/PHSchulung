package de.internal.awareness.web.api.dto;

import java.time.Instant;

/**
 * JSON-Uebertragungsobjekt fuer ein einzelnes Ereignis der Zustell-Timeline.
 *
 * <p>Der Zeitpunkt wird als roher {@link Instant} ausgegeben (Jackson: ISO-8601-Zeichenkette, UTC); die
 * anzeigebezogene Formatierung ist Aufgabe des Frontends.</p>
 *
 * @param type       Art des Ereignisses als Name (z. B. {@code LINK_CLICK})
 * @param occurredAt Zeitpunkt des Ereignisses (UTC)
 */
public record TimelineEntryDto(String type, Instant occurredAt) {
}
