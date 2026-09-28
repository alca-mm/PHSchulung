package de.internal.awareness.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sicherheits- und Korrektheitstests fuer {@link CsvGenerator}: UTF-8 mit BOM, RFC-4180-Aufbau (CRLF,
 * Quoting), Tab-Aufteilung von Inhaltszeilen in Felder und vollstaendige Entwertung von Formel-Injection in
 * JEDEM Feld (Titel, Untertitel, Inhalt).
 *
 * <p>Die Ausgabe wird zusaetzlich mit einem kleinen, strikten RFC-4180-Parser (in diesem Test) zurueckgelesen,
 * damit nicht nur Stringvergleiche, sondern auch die tatsaechliche Feldstruktur geprueft wird.</p>
 */
class CsvGeneratorTest {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** Alle Zeichen, die am Feldanfang als Formelstart gelten (inkl. Fullwidth-Varianten). */
    private static final List<Character> TRIGGERS = List.of(
            '=', '+', '-', '@', '\t', '\r', '\n', '\uFF1D', '\uFF0B', '\uFF0D', '\uFF20');

    private final CsvGenerator generator = new CsvGenerator();

    // ---------------------------------------------------------------- Hilfsfunktionen

    /** Dekodiert strikt als UTF-8: ungueltige Bytefolgen fuehren zu einer Exception statt zu Ersatzzeichen. */
    private static String decodeStrictUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** Erzeugt die CSV, prueft die BOM und liefert den Text OHNE BOM. */
    private String generateCsvText(String title, String subtitle, String content) throws CharacterCodingException {
        byte[] csv = generator.generate(new FileContentRequest(title, subtitle, content));
        assertThat(Arrays.copyOf(csv, Math.min(3, csv.length))).as("UTF-8-BOM").isEqualTo(BOM);
        String text = decodeStrictUtf8(csv);
        assertThat(text).startsWith("\uFEFF");
        return text.substring(1);
    }

    /** Trigger, die an einem (auch internen) Zellstart entwertet werden. */
    private static final List<Character> CELL_TRIGGERS = List.of(
            '=', '+', '-', '@', '\uFF1D', '\uFF0B', '\uFF0D', '\uFF20');

    /**
     * Unabhaengiges Modell der Zellstart-Regel (bewusst anders formuliert als die Implementierung): am
     * Feldanfang oder nach , ; Tab CR LF, gefolgt von beliebig vielen {@code "}, dann ein Trigger.
     */
    private static final Pattern CELL_START_TRIGGER = Pattern.compile(
            "(^|[,;\t\r\n])(\"*)([=+@\uFF1D\uFF0B\uFF0D\uFF20-])");

    /** Erwarteter (entwerteter, aber ungequoteter) Feldwert laut Sanitizer-Regeln. */
    private static String expectedFieldValue(String value) {
        String neutralized = CELL_START_TRIGGER.matcher(value).replaceAll("$1$2'$3");
        if (!value.isEmpty() && (value.charAt(0) == '\t' || value.charAt(0) == '\r' || value.charAt(0) == '\n')) {
            // Tab/CR/LF sind nur am Feldanfang selbst Trigger (Regel 1).
            neutralized = "'" + neutralized;
        }
        return neutralized;
    }

    /** Erwartete Datensaetze laut Generator-Aufbau (Eingaben ohne Steuerzeichen ausser Tab/CR/LF). */
    private static List<List<String>> expectedRecords(String title, String subtitle, String content) {
        List<List<String>> expected = new ArrayList<>();
        if (title != null && !title.isBlank()) {
            expected.add(List.of(expectedFieldValue(title)));
        }
        if (subtitle != null && !subtitle.isBlank()) {
            expected.add(List.of(expectedFieldValue(subtitle)));
        }
        if (content != null && !content.isBlank()) {
            for (String line : content.split("\n", -1)) {
                String record = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                expected.add(Arrays.stream(record.split("\t", -1)).map(CsvGeneratorTest::expectedFieldValue).toList());
            }
        }
        return expected;
    }

    /**
     * "Feindlicher" Parser (Modell fuer deutsches Excel und den alten Textimport-Assistenten): trennt die rohe
     * Ausgabe an , ; Tab CR LF OHNE Beachtung von Anfuehrungszeichen, entfernt fuehrende {@code "} jedes Stuecks
     * und verlangt, dass kein Stueck mit {@code = + - @} oder einer Fullwidth-Variante beginnt.
     */
    private static void assertNoHostileCellStartsWithTrigger(String csvWithoutBom) {
        for (String piece : csvWithoutBom.split("[,;\t\r\n]", -1)) {
            String stripped = piece.replaceFirst("^\"+", "");
            if (!stripped.isEmpty()) {
                assertThat(CELL_TRIGGERS).as("Zellstart '%s'", piece).doesNotContain(stripped.charAt(0));
            }
        }
    }

    /**
     * Kleiner, STRIKTER RFC-4180-Parser: Felder durch Komma getrennt, Datensaetze durch CRLF getrennt UND
     * abgeschlossen, Quoting mit {@code "} und verdoppeltem {@code ""}. Nacktes CR/LF ausserhalb von
     * Anfuehrungszeichen, ein Anfuehrungszeichen mitten in einem ungequoteten Feld oder ein unterminiertes
     * Quoting fuehren zu einem {@link AssertionError}.
     */
    static List<List<String>> parseRfc4180(String csv) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldWasQuoted = false;
        int i = 0;
        int n = csv.length();
        while (i < n) {
            char c = csv.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    if (i < n && csv.charAt(i) != ',' && csv.charAt(i) != '\r') {
                        throw new AssertionError("Zeichen nach schliessendem Anfuehrungszeichen an Position " + i);
                    }
                    continue;
                }
                field.append(c);
                i++;
                continue;
            }
            if (c == '"') {
                if (field.length() != 0 || fieldWasQuoted) {
                    throw new AssertionError("Anfuehrungszeichen in ungequotetem Feld an Position " + i);
                }
                inQuotes = true;
                fieldWasQuoted = true;
                i++;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                fieldWasQuoted = false;
                i++;
            } else if (c == '\r') {
                if (i + 1 >= n || csv.charAt(i + 1) != '\n') {
                    throw new AssertionError("Nacktes CR ausserhalb von Anfuehrungszeichen an Position " + i);
                }
                fields.add(field.toString());
                field.setLength(0);
                fieldWasQuoted = false;
                records.add(fields);
                fields = new ArrayList<>();
                i += 2;
            } else if (c == '\n') {
                throw new AssertionError("Nacktes LF ausserhalb von Anfuehrungszeichen an Position " + i);
            } else {
                field.append(c);
                i++;
            }
        }
        if (inQuotes) {
            throw new AssertionError("Nicht abgeschlossenes Anfuehrungszeichen");
        }
        if (field.length() != 0 || fieldWasQuoted || !fields.isEmpty()) {
            throw new AssertionError("Letzter Datensatz nicht mit CRLF abgeschlossen");
        }
        return records;
    }

    private List<List<String>> generateAndParse(String title, String subtitle, String content)
            throws CharacterCodingException {
        return parseRfc4180(generateCsvText(title, subtitle, content));
    }

    static Stream<Character> triggers() {
        return TRIGGERS.stream();
    }

    /** Trigger, die am Anfang eines Inhaltsfelds landen koennen (Tab und LF sind dort Trennzeichen). */
    static Stream<Character> contentFieldTriggers() {
        return TRIGGERS.stream().filter(c -> c != '\t' && c != '\n');
    }

    // ---------------------------------------------------------------- Typ / Grundverhalten

    @Test
    void typeIsCsvWithTextCsvUtf8() {
        assertThat(generator.type()).isEqualTo(GeneratedFileType.CSV);
        assertThat(generator.type().extension()).isEqualTo("csv");
        assertThat(generator.type().contentType()).isEqualTo("text/csv;charset=UTF-8");
    }

    @Test
    void nullRequestIsRejected() {
        assertThatThrownBy(() -> generator.generate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Kein Inhalt.");
    }

    @Test
    void completelyEmptyRequestProducesOnlyBom() {
        assertThat(generator.generate(new FileContentRequest(null, null, null))).isEqualTo(BOM);
        assertThat(generator.generate(new FileContentRequest("", "", ""))).isEqualTo(BOM);
        assertThat(generator.generate(new FileContentRequest("  ", "\t", " \r\n "))).isEqualTo(BOM);
    }

    @Test
    void startsWithExactlyOneUtf8Bom() {
        byte[] csv = generator.generate(new FileContentRequest("Titel", null, "Inhalt"));
        assertThat(Arrays.copyOf(csv, 3)).isEqualTo(BOM);
        assertThat(csv[3]).isEqualTo((byte) 'T');
    }

    @Test
    void outputIsStrictlyValidUtf8() {
        byte[] csv = generator.generate(new FileContentRequest("Gr\u00FC\u00DFe", "\u20AC \uD83D\uDE00", "\u00E4\t\u00F6\n\u00FC"));
        assertThatCode(() -> decodeStrictUtf8(csv)).doesNotThrowAnyException();
    }

    @Test
    void producesExactExpectedOutputForSample() throws Exception {
        String text = generateCsvText(
                "Kundenliste",
                "Stand: 09/2026",
                "Name\tBetrag\tOrt\nM\u00FCller\t1,50\tK\u00F6ln\n\nSumme\t=SUM(B2:B3)\t");
        assertThat(text).isEqualTo(
                "Kundenliste\r\n"
                        + "Stand: 09/2026\r\n"
                        + "Name,Betrag,Ort\r\n"
                        + "M\u00FCller,\"1,50\",K\u00F6ln\r\n"
                        + "\r\n"
                        + "Summe,'=SUM(B2:B3),\r\n");
    }

    @Test
    void contentOnlyProducesOnlyContentRecords() throws Exception {
        assertThat(generateCsvText(null, "   ", "a\tb")).isEqualTo("a,b\r\n");
    }

    @Test
    void titleAndSubtitleWithoutContentProduceTwoRecords() throws Exception {
        assertThat(generateCsvText("Titel", "Untertitel", null)).isEqualTo("Titel\r\nUntertitel\r\n");
    }

    @Test
    void crlfAndLfContentLinesBecomeCrlfRecordsWithoutStrayCarriageReturn() throws Exception {
        assertThat(generateCsvText(null, null, "a\r\nb\nc")).isEqualTo("a\r\nb\r\nc\r\n");
    }

    @Test
    void tabSplitKeepsEmptyColumns() throws Exception {
        assertThat(generateAndParse(null, null, "a\t\tb\t"))
                .containsExactly(List.of("a", "", "b", ""));
    }

    @Test
    void emptyContentLineBecomesEmptyRecord() throws Exception {
        assertThat(generateAndParse(null, null, "a\n\nb"))
                .containsExactly(List.of("a"), List.of(""), List.of("b"));
    }

    @Test
    void titleWithTabOrLineBreakStaysOneQuotedField() throws Exception {
        String text = generateCsvText("A\tB", "Zeile1\nZeile2", null);
        assertThat(text).isEqualTo("\"A\tB\"\r\n\"Zeile1\nZeile2\"\r\n");
        assertThat(parseRfc4180(text)).containsExactly(List.of("A\tB"), List.of("Zeile1\nZeile2"));
    }

    // ---------------------------------------------------------------- Formula-Injection

    @ParameterizedTest
    @MethodSource("triggers")
    void neutralizesFormulaTriggerInTitle(Character trigger) throws Exception {
        String payload = trigger + "HYPERLINK(\"http://example.invalid\",\"x\")";
        List<List<String>> records = generateAndParse(payload, null, null);
        assertThat(records).containsExactly(List.of("'" + payload));
    }

    @ParameterizedTest
    @MethodSource("triggers")
    void neutralizesFormulaTriggerInSubtitle(Character trigger) throws Exception {
        String payload = trigger + "1+1";
        List<List<String>> records = generateAndParse("Titel", payload, null);
        assertThat(records).containsExactly(List.of("Titel"), List.of("'" + payload));
    }

    @ParameterizedTest
    @MethodSource("contentFieldTriggers")
    void neutralizesFormulaTriggerInEveryContentField(Character trigger) throws Exception {
        String payload = trigger + "cmd|' /C calc'!A0";
        // Trigger im ersten Feld einer Zeile und in tab-getrennten Folgefeldern.
        String content = payload + "\tneutral\t" + payload + "\n" + "x\t" + payload;
        List<List<String>> records = generateAndParse(null, null, content);
        assertThat(records).containsExactly(
                List.of("'" + payload, "neutral", "'" + payload),
                List.of("x", "'" + payload));
    }

    @Test
    void neutralizesAsciiTriggersInTabSplitFieldsExactly() throws Exception {
        String text = generateCsvText(null, null, "x\t=1+1\t+2\t-3\t@4\t\uFF1D5\t\uFF0B6\t\uFF0D7\t\uFF208");
        assertThat(text).isEqualTo("x,'=1+1,'+2,'-3,'@4,'\uFF1D5,'\uFF0B6,'\uFF0D7,'\uFF208\r\n");
    }

    @Test
    void noParsedFieldStartsWithAFormulaTrigger() throws Exception {
        StringBuilder hostile = new StringBuilder();
        for (char trigger : TRIGGERS) {
            hostile.append(trigger).append("1+1\t");
            hostile.append("a;").append(trigger).append("2\t");
            hostile.append("\"").append(trigger).append("3\"\n");
        }
        String title = "=HYPERLINK(\"http://example.invalid\",\"x\")";
        String subtitle = "\uFF20SUM(A1)";
        for (List<String> record : generateAndParse(title, subtitle, hostile.toString())) {
            for (String field : record) {
                if (!field.isEmpty()) {
                    assertThat(TRIGGERS).as("Feld '%s'", field).doesNotContain(field.charAt(0));
                }
            }
        }
    }

    // ---------------------------------------------------------------- Feindlicher Parser (locale-abhaengig)

    /** In Excel 16 de-DE verifizierte Payloads, die ohne interne Zellstart-Entwertung Formeln ergaben. */
    static Stream<String> reviewerVerifiedExcelPayloads() {
        return Stream.of(
                "x\ty;=1+1;z",
                "http://example.invalid/\nx\ty;=HYPERLINK(A1);z",
                "x\ty\r=1+1;z",
                "x\ty;\"=1+1&\t\";z",
                "b\r\n=1+1,z");
    }

    @ParameterizedTest
    @MethodSource("reviewerVerifiedExcelPayloads")
    void reviewerPayloadsCreateNoHostileCellStartInTitleSubtitleOrContent(String payload) throws Exception {
        String[][] requests = {
                {payload, null, null},
                {null, payload, null},
                {null, null, payload},
                {payload, payload, payload}};
        for (String[] request : requests) {
            String text = generateCsvText(request[0], request[1], request[2]);
            assertNoHostileCellStartsWithTrigger(text);
            // Strikter RFC-4180-Round-Trip: Feldwerte == entwertete Werte.
            assertThat(parseRfc4180(text)).isEqualTo(expectedRecords(request[0], request[1], request[2]));
        }
    }

    @Test
    void reviewerPayloadsProduceExactNeutralizedOutput() throws Exception {
        assertThat(generateCsvText(null, null, "x\ty;=1+1;z")).isEqualTo("x,\"y;'=1+1;z\"\r\n");
        assertThat(generateCsvText(null, null, "http://example.invalid/\nx\ty;=HYPERLINK(A1);z"))
                .isEqualTo("http://example.invalid/\r\nx,\"y;'=HYPERLINK(A1);z\"\r\n");
        assertThat(generateCsvText(null, null, "x\ty\r=1+1;z")).isEqualTo("x,\"y\r'=1+1;z\"\r\n");
        assertThat(generateCsvText(null, null, "x\ty;\"=1+1&\t\";z"))
                .isEqualTo("x,\"y;\"\"'=1+1&\",\"\"\";z\"\r\n");
        assertThat(generateCsvText("a", "b\r\n=1+1,z", null)).isEqualTo("a\r\n\"b\r\n'=1+1,z\"\r\n");
    }

    @Test
    void noHostileCellStartForAnySeparatorQuoteTriggerCombination() throws Exception {
        List<String> separators = List.of(",", ";", "\t", "\r", "\n", "\r\n");
        List<String> quoteRuns = List.of("", "\"", "\"\"");
        for (String separator : separators) {
            for (String quotes : quoteRuns) {
                for (char trigger : CELL_TRIGGERS) {
                    String payload = quotes + trigger + "0" + separator + quotes + trigger + "1"
                            + "\t" + quotes + trigger + "2" + separator + "z";
                    String text = generateCsvText(payload, payload, payload);
                    assertNoHostileCellStartsWithTrigger(text);
                    assertThat(parseRfc4180(text)).isEqualTo(expectedRecords(payload, payload, payload));
                }
            }
        }
    }

    // ---------------------------------------------------------------- Quoting / Round-Trip

    @Test
    void quotesFieldsWithCommaSemicolonAndQuotes() throws Exception {
        String text = generateCsvText(null, null, "a,b\tc;d\te\"f\t\"g\"\tplain");
        assertThat(text).isEqualTo("\"a,b\",\"c;d\",\"e\"\"f\",\"\"\"g\"\"\",plain\r\n");
    }

    @Test
    void parsedFieldsEqualSanitizedValues() throws Exception {
        String title = "Titel, mit \"Zitat\"; und =Formel";
        String subtitle = "-5";
        List<String> line1 = List.of("Name", "Betrag", "Notiz");
        List<String> line2 = List.of("M\u00FCller; Hans", "-12,50", "=1+1");
        List<String> line3 = List.of("\"quoted\"", "@user", "a,b;c\"d");
        List<String> line4 = List.of("", "\uFF0Bx", "");
        String content = String.join("\t", line1) + "\r\n"
                + String.join("\t", line2) + "\n"
                + String.join("\t", line3) + "\n"
                + String.join("\t", line4);

        List<List<String>> records = generateAndParse(title, subtitle, content);

        List<List<String>> expected = new ArrayList<>();
        expected.add(List.of(expectedFieldValue(title)));
        expected.add(List.of(expectedFieldValue(subtitle)));
        for (List<String> line : List.of(line1, line2, line3, line4)) {
            expected.add(line.stream().map(CsvGeneratorTest::expectedFieldValue).toList());
        }
        assertThat(records).isEqualTo(expected);
        // Stichproben der konkreten Entwertung.
        assertThat(records.get(1)).containsExactly("'-5");
        assertThat(records.get(3)).containsExactly("M\u00FCller; Hans", "'-12,50", "'=1+1");
    }

    @Test
    void preservesUmlautsAndSpecialCharacters() throws Exception {
        String special = "\u00C4\u00D6\u00DC \u00E4\u00F6\u00FC \u00DF \u20AC \uD83D\uDE00";
        List<List<String>> records = generateAndParse(special, special, special + "\t" + special);
        assertThat(records).containsExactly(List.of(special), List.of(special), List.of(special, special));
    }

    @Test
    void removesControlCharacters() throws Exception {
        List<List<String>> records = generateAndParse("A\u0000B", "C\u001BD", "E\u0001F\tG\uD800H");
        assertThat(records).containsExactly(List.of("AB"), List.of("CD"), List.of("EF", "GH"));
    }

    @Test
    void maximumSizedInputIsWrittenCompletely() throws Exception {
        String title = "T".repeat(254) + "\u00E4";
        String subtitle = "S".repeat(254) + "\u00DF";
        StringBuilder content = new StringBuilder();
        int i = 0;
        while (content.length() < 10_000) {
            content.append("Zeile ").append(i).append('\t').append(i % 3 == 0 ? "=" : "").append(i).append(",5\n");
            i++;
        }
        content.setLength(10_000);
        String contentValue = content.toString();

        List<List<String>> records = generateAndParse(title, subtitle, contentValue);

        List<List<String>> expected = new ArrayList<>();
        expected.add(List.of(title));
        expected.add(List.of(subtitle));
        for (String line : contentValue.split("\n", -1)) {
            expected.add(Arrays.stream(line.split("\t", -1)).map(CsvGeneratorTest::expectedFieldValue).toList());
        }
        assertThat(records).isEqualTo(expected);
    }

    @Test
    void generatorIsReusableAndDeterministic() {
        FileContentRequest request = new FileContentRequest("Titel", "Sub", "a\tb");
        assertThat(generator.generate(request)).isEqualTo(generator.generate(request));
    }
}
