package de.internal.awareness.file;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Erzeugt PASSIVE {@code .csv}-Dateien nach RFC 4180 mit durchgaengigem Schutz gegen CSV-/Formula-Injection.
 *
 * <p>Sicherheitsgrenze (verbindlich): Die Datei enthaelt ausschliesslich Textwerte. JEDES Feld - Titel,
 * Untertitel und jedes Inhaltsfeld - laeuft ueber {@link CsvSanitizer#sanitizeField(String)}: Werte, die mit
 * einem Formel-Trigger ({@code = + - @}, Tab, CR, LF oder Fullwidth-Variante) beginnen, werden mit {@code '}
 * entwertet; ebenso jeder interne potenzielle Zellstart (Trigger nach {@code , ; Tab CR LF}, auch hinter
 * Anfuehrungszeichen), weil locale-abhaengige Importe wie deutsches Excel (Trennzeichen {@code ;}) das
 * Komma-Quoting ignorieren und dort eine neue Zelle bzw. Zeile beginnen. Felder mit Komma,
 * Anfuehrungszeichen, Zeilenumbruch, Semikolon oder Tab werden zusaetzlich gequotet. So entstehen weder
 * Formeln noch Hyperlinks noch DDE-Aufrufe, wenn die Datei in Excel/LibreOffice geoeffnet oder importiert
 * wird. Benutzereingaben werden NICHT geloggt.</p>
 *
 * <p>Aufbau (ein Datensatz je Zeile, Trennzeichen Komma):</p>
 * <ol>
 *   <li>Titel als einzelnes Feld (falls vorhanden),</li>
 *   <li>Untertitel als einzelnes Feld (falls vorhanden),</li>
 *   <li>je Inhaltszeile ein Datensatz; die Zeile wird am Tab in Felder aufgeteilt, damit eine aus Excel
 *       kopierte Tabelle ihre Spalten behaelt. Eine leere Inhaltszeile ergibt einen leeren Datensatz.</li>
 * </ol>
 * <p>Titel/Untertitel werden NICHT aufgeteilt: Tabs oder Zeilenumbrueche darin bleiben Teil des (dann
 * gequoteten) Feldes. Inhaltszeilen entstehen durch Aufteilen am {@code \n} (Limit -1, leere Zeilen bleiben
 * erhalten); ein einzelnes {@code \r} am Zeilenende (CRLF-Eingabe) wird entfernt. {@code null}, leere oder nur
 * aus Leerraum bestehende Felder gelten als "nicht vorhanden".</p>
 *
 * <p>Kodierung und Zeilenenden:</p>
 * <ul>
 *   <li>UTF-8 MIT BOM ({@code EF BB BF}): Excel unter Windows liest eine CSV ohne BOM in der ANSI-Codepage
 *       (z. B. Windows-1252) und zeigt Umlaute dann verstuemmelt an (je Umlaut zwei falsche Zeichen, sog.
 *       Mojibake).
 *       Die BOM laesst Excel UTF-8 erkennen; LibreOffice und gaengige CSV-Bibliotheken kommen damit ebenfalls
 *       zurecht. Eine komplett leere Anfrage ergibt deshalb eine Datei, die nur aus der BOM besteht.</li>
 *   <li>Vor dem Schreiben werden ueber {@link DocumentText#stripXmlIncompatibleChars(String)} Steuerzeichen
 *       (ausser Tab, LF, CR) und einzelne Surrogates entfernt - die UTF-8-Kodierung ist damit verlustfrei.</li>
 *   <li>Datensaetze werden nach RFC 4180 durch CRLF getrennt; auch der letzte Datensatz endet mit CRLF.</li>
 * </ul>
 *
 * <p>Die Klasse ist zustandslos und damit threadsicher.</p>
 */
@Component
public class CsvGenerator implements FileContentGenerator {

    /** UTF-8-Byte-Order-Mark, damit Excel die Datei als UTF-8 erkennt. */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** Datensatz-Trenner/-Abschluss nach RFC 4180. */
    private static final String CRLF = "\r\n";

    /** Oeffentlicher No-Arg-Konstruktor (Spring-Bean, direkt in Tests instanziierbar). */
    public CsvGenerator() {
    }

    @Override
    public GeneratedFileType type() {
        return GeneratedFileType.CSV;
    }

    /**
     * Baut die CSV-Datei aus Titel, Untertitel und Inhalt (siehe Klassenbeschreibung).
     *
     * @param request neutrale Eingabe; einzelne Felder duerfen {@code null}/leer sein
     * @return die Datei als UTF-8-Bytes mit BOM (nur BOM, wenn kein Feld vorhanden ist)
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

        StringBuilder csv = new StringBuilder();
        if (title != null) {
            // Titel bewusst als EIN Feld (keine Aufteilung an Tabs/Zeilenumbruechen).
            csv.append(CsvSanitizer.row(title)).append(CRLF);
        }
        if (subtitle != null) {
            csv.append(CsvSanitizer.row(subtitle)).append(CRLF);
        }
        if (content != null) {
            for (String line : content.split("\n", -1)) {
                String record = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                // Tab-Aufteilung mit Limit -1: leere Spalten (auch am Zeilenende) bleiben erhalten.
                csv.append(CsvSanitizer.row(record.split("\t", -1))).append(CRLF);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(UTF8_BOM.length + csv.length() * 2);
        out.writeBytes(UTF8_BOM);
        out.writeBytes(csv.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /**
     * Entfernt unzulaessige Steuerzeichen/Surrogates und liefert {@code null}, wenn danach nichts Sichtbares
     * uebrig bleibt (null, leer oder nur Leerraum = "nicht vorhanden").
     */
    private static String presentOrNull(String value) {
        String cleaned = DocumentText.stripXmlIncompatibleChars(value);
        return cleaned == null || cleaned.isBlank() ? null : cleaned;
    }
}
