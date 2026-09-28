package de.internal.awareness.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sicherheits- und Korrektheitstests fuer {@link CsvSanitizer}: Entwertung von Formel-Triggern am Feldanfang
 * (inkl. Fullwidth-Varianten) UND an jedem internen potenziellen Zellstart (nach , ; Tab CR LF, auch hinter
 * Anfuehrungszeichen), RFC-4180-Quoting (inkl. Semikolon und Tab) und Zeilenaufbau.
 *
 * <p>Die Eingaben enthalten bewusst Steuerzeichen (Tab/CR/LF) - diese werden direkt als Java-Strings
 * uebergeben, nicht ueber {@code @CsvSource}, damit keine Parser-Eigenheiten die Tests verfaelschen.</p>
 */
class CsvSanitizerTest {

    // ---------------------------------------------------------------- Formel-Trigger

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+1", "-1", "@SUM(A1:A2)", "=A1", "+A1*2", "-2+3", "@x"})
    void prefixesAsciiFormulaTriggersWithSingleQuote(String value) {
        assertThat(CsvSanitizer.sanitizeField(value)).isEqualTo("'" + value);
    }

    @Test
    void prefixesAndQuotesLeadingTab() {
        // Tab ist Trigger UND Quoting-Zeichen -> erst Praefix, dann in Anfuehrungszeichen.
        assertThat(CsvSanitizer.sanitizeField("\tX")).isEqualTo("\"'\tX\"");
    }

    @Test
    void prefixesAndQuotesLeadingCarriageReturn() {
        assertThat(CsvSanitizer.sanitizeField("\rX")).isEqualTo("\"'\rX\"");
    }

    @Test
    void prefixesAndQuotesLeadingLineFeed() {
        assertThat(CsvSanitizer.sanitizeField("\nX")).isEqualTo("\"'\nX\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\uFF1D1+1", "\uFF0B1", "\uFF0D1", "\uFF20SUM(A1)"})
    void prefixesFullwidthFormulaTriggers(String value) {
        // Defense-in-depth: Fullwidth = + - @ (U+FF1D, U+FF0B, U+FF0D, U+FF20).
        assertThat(CsvSanitizer.sanitizeField(value)).isEqualTo("'" + value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"=", "+", "-", "@", "\uFF1D", "\uFF0B", "\uFF0D", "\uFF20"})
    void prefixesSingleCharacterTriggerFields(String value) {
        assertThat(CsvSanitizer.sanitizeField(value)).isEqualTo("'" + value);
    }

    @Test
    void prefixesAndQuotesSingleCharacterWhitespaceTriggers() {
        assertThat(CsvSanitizer.sanitizeField("\t")).isEqualTo("\"'\t\"");
        assertThat(CsvSanitizer.sanitizeField("\r")).isEqualTo("\"'\r\"");
        assertThat(CsvSanitizer.sanitizeField("\n")).isEqualTo("\"'\n\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hallo Welt", "123", "12.5", "Gruesse aus Muenchen", "Gr\u00FC\u00DFe \u00E4\u00F6\u00FC \u20AC",
            "a=b", "1-2", "x@example.invalid", "Summe: =A1", " =1+1", "'bereits Text", "a|b!c"})
    void leavesPlainValuesUnchanged(String value) {
        assertThat(CsvSanitizer.sanitizeField(value)).isEqualTo(value);
    }

    @Test
    void negativeNumberIsPrefixedAsDocumentedTradeOff() {
        // Bewusster Kompromiss: Sicherheit vor Zahlentyp - "-5" wird als Text "'-5" ausgegeben.
        assertThat(CsvSanitizer.sanitizeField("-5")).isEqualTo("'-5");
    }

    @Test
    void nullAndEmptyBecomeEmptyField() {
        assertThat(CsvSanitizer.sanitizeField(null)).isEmpty();
        assertThat(CsvSanitizer.sanitizeField("")).isEmpty();
    }

    // ---------------------------------------------------------------- Quoting

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "a,b|`\"a,b\"`",
            "a;b|`\"a;b\"`",
            "a\"b|`\"a\"\"b\"`",
            "Preis: 1,50 \u20AC|`\"Preis: 1,50 \u20AC\"`"
    })
    void quotesFieldsWithDelimiterOrQuote(String value, String expected) {
        assertThat(CsvSanitizer.sanitizeField(value)).isEqualTo(expected);
    }

    @Test
    void quotesFieldContainingTab() {
        assertThat(CsvSanitizer.sanitizeField("a\tb")).isEqualTo("\"a\tb\"");
    }

    @Test
    void quotesFieldContainingLineFeed() {
        assertThat(CsvSanitizer.sanitizeField("a\nb")).isEqualTo("\"a\nb\"");
    }

    @Test
    void quotesFieldContainingCarriageReturn() {
        assertThat(CsvSanitizer.sanitizeField("a\rb")).isEqualTo("\"a\rb\"");
        assertThat(CsvSanitizer.sanitizeField("a\r\nb")).isEqualTo("\"a\r\nb\"");
    }

    @Test
    void semicolonHidingAFormulaIsNeutralizedAndQuoted() {
        // Deutsches Excel trennt am ';' und ignoriert dabei das Quoting -> "=1+1" waere eine eigene Formelzelle.
        assertThat(CsvSanitizer.sanitizeField("a;=1+1")).isEqualTo("\"a;'=1+1\"");
    }

    @Test
    void tabHidingAFormulaIsNeutralizedAndQuoted() {
        // Tab-getrennter Import wuerde "=1+1" in eine eigene Zelle verschieben.
        assertThat(CsvSanitizer.sanitizeField("a\t=1+1")).isEqualTo("\"a\t'=1+1\"");
    }

    @Test
    void combinesTriggerPrefixAndQuoting() {
        assertThat(CsvSanitizer.sanitizeField("=1,2")).isEqualTo("\"'=1,2\"");
        assertThat(CsvSanitizer.sanitizeField("=1;2")).isEqualTo("\"'=1;2\"");
        // Fuehrender Tab: Praefix am Feldanfang UND Entwertung der Zelle, die nach dem Tab beginnt.
        assertThat(CsvSanitizer.sanitizeField("\t=1")).isEqualTo("\"'\t'=1\"");
        assertThat(CsvSanitizer.sanitizeField("@a\"b")).isEqualTo("\"'@a\"\"b\"");
    }

    @Test
    void leadingDoubleQuoteBeforeTriggerIsSkippedAndNeutralized() {
        // Der Feldanfang ist selbst ein Zellstart: fuehrende Anfuehrungszeichen werden uebersprungen.
        assertThat(CsvSanitizer.sanitizeField("\"=1\"")).isEqualTo("\"\"\"'=1\"\"\"");
        assertThat(CsvSanitizer.sanitizeField("\"\"+1")).isEqualTo("\"\"\"\"\"'+1\"");
        assertThat(CsvSanitizer.sanitizeField("\"")).isEqualTo("\"\"\"\"");
        assertThat(CsvSanitizer.sanitizeField("\"text\"")).isEqualTo("\"\"\"text\"\"\"");
    }

    // ---------------------------------------------------------------- interne Zellstarts

    /** Separatoren, an denen eine locale-abhaengige Tabellenkalkulation eine neue Zelle/Zeile beginnen kann. */
    private static final List<String> SEPARATORS = List.of(",", ";", "\t", "\r", "\n", "\r\n");

    /** Anfuehrungszeichen-Folgen zwischen Separator und Trigger (werden uebersprungen). */
    private static final List<String> QUOTE_RUNS = List.of("", "\"", "\"\"", "\"\"\"");

    /** Trigger, die an einem internen Zellstart entwertet werden. */
    private static final List<String> CELL_TRIGGERS = List.of(
            "=", "+", "-", "@", "\uFF1D", "\uFF0B", "\uFF0D", "\uFF20");

    static Stream<Arguments> separatorQuoteTriggerCombinations() {
        List<Arguments> arguments = new ArrayList<>();
        for (String separator : SEPARATORS) {
            for (String quotes : QUOTE_RUNS) {
                for (String trigger : CELL_TRIGGERS) {
                    arguments.add(Arguments.of(separator, quotes, trigger));
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("separatorQuoteTriggerCombinations")
    void neutralizesTriggerAtEveryInternalCellStart(String separator, String quotes, String trigger) {
        String value = "a" + separator + quotes + trigger + "1+1";
        // Erwartet: Apostroph direkt vor dem Trigger (hinter den Anfuehrungszeichen), dann RFC-4180-Quoting.
        String neutralized = "a" + separator + quotes + "'" + trigger + "1+1";
        String expected = "\"" + neutralized.replace("\"", "\"\"") + "\"";
        assertThat(CsvSanitizer.sanitizeField(value)).isEqualTo(expected);
    }

    @Test
    void neutralizesEveryInternalCellStartInOneValue() {
        String value = "=1;=2,+3\t-4\r@5\n\uFF1D6\r\n\uFF0B7;\"\uFF0D8,\"\"\uFF209";
        assertThat(CsvSanitizer.sanitizeField(value)).isEqualTo(
                "\"'=1;'=2,'+3\t'-4\r'@5\n'\uFF1D6\r\n'\uFF0B7;\"\"'\uFF0D8,\"\"\"\"'\uFF209\"");
    }

    @Test
    void neutralizesTriggerAfterConsecutiveOrLeadingSeparators() {
        assertThat(CsvSanitizer.sanitizeField("a;;=1")).isEqualTo("\"a;;'=1\"");
        assertThat(CsvSanitizer.sanitizeField(";=1")).isEqualTo("\";'=1\"");
        assertThat(CsvSanitizer.sanitizeField(",\t;=1")).isEqualTo("\",\t;'=1\"");
        assertThat(CsvSanitizer.sanitizeField("\r\n=1")).isEqualTo("\"'\r\n'=1\"");
    }

    @Test
    void reviewerVerifiedExcelPayloadsAreNeutralized() {
        // Payloads, die in Excel 16 de-DE ohne interne Entwertung eine Formel ergaben.
        assertThat(CsvSanitizer.sanitizeField("y;=1+1;z")).isEqualTo("\"y;'=1+1;z\"");
        assertThat(CsvSanitizer.sanitizeField("y;=HYPERLINK(A1);z")).isEqualTo("\"y;'=HYPERLINK(A1);z\"");
        assertThat(CsvSanitizer.sanitizeField("y\r=1+1;z")).isEqualTo("\"y\r'=1+1;z\"");
        assertThat(CsvSanitizer.sanitizeField("y;\"=1+1&")).isEqualTo("\"y;\"\"'=1+1&\"");
        assertThat(CsvSanitizer.sanitizeField("b\r\n=1+1,z")).isEqualTo("\"b\r\n'=1+1,z\"");
    }

    @Test
    void internalTriggerWithoutPrecedingSeparatorIsUnchanged() {
        assertThat(CsvSanitizer.sanitizeField("a=b+c-d@e")).isEqualTo("a=b+c-d@e");
        assertThat(CsvSanitizer.sanitizeField("x@example.invalid")).isEqualTo("x@example.invalid");
    }

    @Test
    void whitespaceBetweenSeparatorAndTriggerIsNotNeutralized() {
        // In Excel verifiziert: fuehrender Leerraum vor dem Trigger wird als Text angezeigt.
        assertThat(CsvSanitizer.sanitizeField("a; =1")).isEqualTo("\"a; =1\"");
        assertThat(CsvSanitizer.sanitizeField("a, -b")).isEqualTo("\"a, -b\"");
    }

    @Test
    void internalCellStartRuleTradesDataFidelityForSafety() {
        // Bewusster Kompromiss: auch harmlose Werte erhalten ein Apostroph.
        assertThat(CsvSanitizer.sanitizeField("a,-b")).isEqualTo("\"a,'-b\"");
        assertThat(CsvSanitizer.sanitizeField("Liste:\n- Punkt")).isEqualTo("\"Liste:\n'- Punkt\"");
        assertThat(CsvSanitizer.sanitizeField("Preis: 1,50")).isEqualTo("\"Preis: 1,50\"");
    }

    @Test
    void noPieceOfAQuoteIgnoringSplitStartsWithATrigger() {
        // Modell eines "feindlichen" Parsers: trennt an , ; Tab CR LF OHNE Beachtung von Anfuehrungszeichen und
        // entfernt fuehrende Anfuehrungszeichen - kein Stueck darf dann mit einem Trigger beginnen.
        List<String> values = new ArrayList<>();
        for (String separator : SEPARATORS) {
            for (String quotes : QUOTE_RUNS) {
                for (String trigger : CELL_TRIGGERS) {
                    values.add(quotes + trigger + "1" + separator + quotes + trigger + "2" + separator);
                    values.add("\t" + quotes + trigger + "3");
                }
            }
        }
        for (String value : values) {
            String sanitized = CsvSanitizer.sanitizeField(value);
            for (String piece : sanitized.split("[,;\t\r\n]", -1)) {
                String stripped = piece.replaceFirst("^\"+", "");
                if (!stripped.isEmpty()) {
                    assertThat(CELL_TRIGGERS).as("Stueck '%s' aus '%s'", piece, sanitized)
                            .doesNotContain(stripped.substring(0, 1));
                }
            }
        }
    }

    @Test
    void neutralizesDdePayload() {
        String dde = "=cmd|' /C calc'!A0";
        assertThat(CsvSanitizer.sanitizeField(dde)).isEqualTo("'=cmd|' /C calc'!A0");
    }

    @Test
    void neutralizesHyperlinkFormulaAndDoublesItsQuotes() {
        String hyperlink = "=HYPERLINK(\"http://example.invalid\",\"x\")";
        assertThat(CsvSanitizer.sanitizeField(hyperlink))
                .isEqualTo("\"'=HYPERLINK(\"\"http://example.invalid\"\",\"\"x\"\")\"");
    }

    @Test
    void sanitizedFieldNeverStartsWithATriggerCharacter() {
        String[] triggers = {"=", "+", "-", "@", "\t", "\r", "\n", "\uFF1D", "\uFF0B", "\uFF0D", "\uFF20"};
        for (String trigger : triggers) {
            String sanitized = CsvSanitizer.sanitizeField(trigger + "1+1");
            // Nach einem optionalen oeffnenden Anfuehrungszeichen muss das Apostroph stehen.
            String unquoted = sanitized.startsWith("\"") ? sanitized.substring(1) : sanitized;
            assertThat(unquoted).as("Trigger U+%04X", (int) trigger.charAt(0)).startsWith("'");
        }
    }

    // ---------------------------------------------------------------- row()

    @Test
    void rowJoinsSanitizedFieldsWithComma() {
        assertThat(CsvSanitizer.row("a", "=1", "b,c", null, "", "d\"e"))
                .isEqualTo("a,'=1,\"b,c\",,,\"d\"\"e\"");
    }

    @Test
    void rowWithSingleFieldHasNoSeparator() {
        assertThat(CsvSanitizer.row("nur ein Feld")).isEqualTo("nur ein Feld");
        assertThat(CsvSanitizer.row("")).isEmpty();
    }

    @Test
    void rowWithoutFieldsIsEmpty() {
        assertThat(CsvSanitizer.row()).isEmpty();
        assertThat(CsvSanitizer.row((String[]) null)).isEmpty();
    }
}
