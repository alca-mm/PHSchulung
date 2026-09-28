package de.internal.awareness.file;

/**
 * Erzeugt fuer genau einen {@link GeneratedFileType} den Byte-Inhalt einer PASSIVEN Trainingsdatei aus einem
 * neutralen {@link FileContentRequest}.
 *
 * <p>Jede Implementierung ist ein Spring-{@code @Component}; {@link GeneratedFileService} sammelt alle
 * Implementierungen ein und waehlt sie ueber {@link #type()} aus. So genuegt fuer einen neuen Dateityp eine
 * neue Generator-Bean (plus Enum-Konstante und Migration) - ohne Aenderung an der zentralen Dispatch-Logik.</p>
 *
 * <p>Sicherheitsgrenze (verbindlich): Es werden ausschliesslich passive, ungefaehrliche Dokumente erzeugt -
 * keine Makros, keine ausfuehrbaren/eingebetteten Inhalte, keine externen/automatischen Referenzen.</p>
 */
public interface FileContentGenerator {

    /** Der Dateityp, den dieser Generator erzeugt. */
    GeneratedFileType type();

    /** Erzeugt den vollstaendigen Datei-Inhalt im Speicher. */
    byte[] generate(FileContentRequest request);
}
