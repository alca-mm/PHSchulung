package de.internal.awareness.file;

/**
 * Neutrales Eingabemodell fuer die Erzeugung passiver Trainingsdateien. Bewusst formatunabhaengig gehalten,
 * damit nicht fuer jeden Dateityp ein eigener Editor noetig ist: Ein {@link FileContentGenerator} interpretiert
 * {@code title}/{@code subtitle}/{@code content} passend fuer sein Format (Tabellenformate duerfen daraus eine
 * einfache, sichere Beispieltabelle ableiten).
 *
 * @param title    Titel/Ueberschrift (optional, kann leer sein)
 * @param subtitle optionaler Untertitel (von einigen Formaten genutzt, z. B. DOCX/PDF/PPTX)
 * @param content  Freitext-Inhalt (optional)
 */
public record FileContentRequest(String title, String subtitle, String content) {
}
