package de.internal.awareness.file;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Erzeugt PASSIVE {@code .txt}-Dateien: reiner Text, kodiert als UTF-8 OHNE BOM, Zeilenende CRLF.
 *
 * <p>Sicherheitsgrenze (verbindlich): Eine Textdatei enthaelt ausschliesslich Zeichen - keine Makros, keine
 * eingebetteten Objekte, keine automatischen Netzwerkzugriffe. Benutzereingaben werden unveraendert als Text
 * geschrieben (nichts wird interpretiert oder ausgefuehrt) und NICHT geloggt.</p>
 *
 * <p>Aufbau der Datei:</p>
 * <ol>
 *   <li>Titelzeile (falls vorhanden),</li>
 *   <li>Untertitelzeile (falls vorhanden),</li>
 *   <li>genau eine Leerzeile - nur wenn eine der Kopfzeilen geschrieben wurde UND Inhalt folgt,</li>
 *   <li>die Inhaltszeilen (leere Zeilen im Inhalt bleiben erhalten).</li>
 * </ol>
 * <p>{@code null}, leere oder nur aus Leerraum bestehende Felder gelten als "nicht vorhanden". Vorhandene Felder
 * werden NICHT getrimmt, damit der eingegebene Text vollstaendig erhalten bleibt. Eine komplett leere Anfrage
 * ergibt eine leere Datei (0 Bytes).</p>
 *
 * <p>Zeichen und Zeilenenden:</p>
 * <ul>
 *   <li>Zuerst werden ueber {@link DocumentText#stripXmlIncompatibleChars(String)} Steuerzeichen (ausser Tab,
 *       LF, CR) und einzelne Surrogates entfernt - so ist die UTF-8-Kodierung verlustfrei und es landen keine
 *       Terminal-Escape-Sequenzen o. ae. in der Datei. Tabs bleiben erhalten.</li>
 *   <li>ALLE Zeilenumbrueche ({@code \r\n}, {@code \n} und auch ein einzelnes {@code \r}) werden zu CRLF
 *       normalisiert - auch in Titel/Untertitel. CRLF ist das native Zeilenende unter Windows (Zielumgebung der
 *       Trainings) und wird von allen gaengigen Editoren korrekt angezeigt. Jede geschriebene Zeile endet mit
 *       CRLF; eine nicht leere Datei endet daher immer mit CRLF.</li>
 *   <li>UTF-8 ohne BOM: Eine BOM ist fuer UTF-8 nicht noetig, aktuelle Editoren erkennen UTF-8 zuverlaessig,
 *       und manche Werkzeuge zeigen eine BOM als stoerende Zeichen am Dateianfang an.</li>
 * </ul>
 *
 * <p>Die Klasse ist zustandslos und damit threadsicher.</p>
 */
@Component
public class TxtGenerator implements FileContentGenerator {

    /** Zeilenende fuer alle geschriebenen Zeilen (Windows-Konvention). */
    private static final String CRLF = "\r\n";

    /** Oeffentlicher No-Arg-Konstruktor (Spring-Bean, direkt in Tests instanziierbar). */
    public TxtGenerator() {
    }

    @Override
    public GeneratedFileType type() {
        return GeneratedFileType.TXT;
    }

    /**
     * Baut die Textdatei aus Titel, Untertitel und Inhalt (siehe Klassenbeschreibung).
     *
     * @param request neutrale Eingabe; einzelne Felder duerfen {@code null}/leer sein
     * @return die Datei als UTF-8-Bytes ohne BOM (leer, wenn kein Feld vorhanden ist)
     * @throws IllegalArgumentException wenn {@code request} {@code null} ist
     */
    @Override
    public byte[] generate(FileContentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Kein Inhalt.");
        }
        String title = presentOrNull(request.title());
        String subtitle = presentOrNull(request.subtitle());
        String content = presentOrNull(request.content());

        StringBuilder text = new StringBuilder();
        boolean headerWritten = false;
        if (title != null) {
            appendLines(text, title);
            headerWritten = true;
        }
        if (subtitle != null) {
            appendLines(text, subtitle);
            headerWritten = true;
        }
        if (content != null) {
            if (headerWritten) {
                // Genau eine Leerzeile trennt Kopf und Inhalt.
                text.append(CRLF);
            }
            appendLines(text, content);
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Entfernt unzulaessige Steuerzeichen/Surrogates und liefert {@code null}, wenn danach nichts Sichtbares
     * uebrig bleibt (null, leer oder nur Leerraum = "nicht vorhanden").
     */
    private static String presentOrNull(String value) {
        String cleaned = DocumentText.stripXmlIncompatibleChars(value);
        return cleaned == null || cleaned.isBlank() ? null : cleaned;
    }

    /**
     * Haengt den Text zeilenweise an, jede Zeile mit CRLF abgeschlossen. CRLF, LF und einzelnes CR gelten
     * jeweils als ein Zeilenumbruch; ein abschliessender Umbruch im Text erzeugt (wie bei den anderen
     * Generatoren, Split mit Limit -1) eine abschliessende Leerzeile.
     */
    private static void appendLines(StringBuilder target, String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n", -1)) {
            target.append(line).append(CRLF);
        }
    }
}
