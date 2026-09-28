package de.internal.awareness.recipient;

import java.util.List;

/**
 * Ergebnis eines Massenimports von Empfaengern in eine Kampagne.
 *
 * @param added        Anzahl der neu angelegten Empfaenger
 * @param duplicates   Anzahl der uebersprungenen Duplikate (bereits in der Kampagne vorhanden oder
 *                     mehrfach im selben Import enthalten)
 * @param invalidLines ungueltige Eingabezeilen (getrimmter Originalwortlaut) fuer eine verstaendliche
 *                     Rueckmeldung an den Benutzer
 */
public record BulkImportResult(int added, int duplicates, List<String> invalidLines) {
}
