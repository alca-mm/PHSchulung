package de.internal.awareness.file;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Korrektheitstests fuer {@link TxtGenerator}: striktes UTF-8 ohne BOM, exakter Aufbau (Titel, Untertitel,
 * Leerzeile, Inhalt), CRLF-Normalisierung, Erhalt aller Zeichen und Entfernen von Steuerzeichen.
 */
class TxtGeneratorTest {

    private final TxtGenerator generator = new TxtGenerator();

    /** Dekodiert strikt als UTF-8: ungueltige Bytefolgen fuehren zu einer Exception statt zu Ersatzzeichen. */
    private static String decodeStrictUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private String generateText(String title, String subtitle, String content) throws CharacterCodingException {
        return decodeStrictUtf8(generator.generate(new FileContentRequest(title, subtitle, content)));
    }

    @Test
    void typeIsTxtWithTextPlainUtf8() {
        assertThat(generator.type()).isEqualTo(GeneratedFileType.TXT);
        assertThat(generator.type().extension()).isEqualTo("txt");
        assertThat(generator.type().contentType()).isEqualTo("text/plain;charset=UTF-8");
    }

    @Test
    void nullRequestIsRejected() {
        assertThatThrownBy(() -> generator.generate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Kein Inhalt.");
    }

    @Test
    void completelyEmptyRequestProducesEmptyFile() {
        assertThat(generator.generate(new FileContentRequest(null, null, null))).isEmpty();
        assertThat(generator.generate(new FileContentRequest("", "", ""))).isEmpty();
        assertThat(generator.generate(new FileContentRequest("   ", "\t", " \r\n \n"))).isEmpty();
    }

    @Test
    void hasNoByteOrderMark() {
        byte[] txt = generator.generate(new FileContentRequest("Titel", null, "Inhalt"));
        assertThat(txt).isNotEmpty();
        assertThat(txt[0]).isEqualTo((byte) 'T');
        assertThat(txt.length >= 3 && txt[0] == (byte) 0xEF && txt[1] == (byte) 0xBB && txt[2] == (byte) 0xBF)
                .as("keine UTF-8-BOM").isFalse();
    }

    @Test
    void outputIsStrictlyValidUtf8() {
        byte[] txt = generator.generate(new FileContentRequest("Gr\u00FC\u00DFe", "\u20AC \uD83D\uDE00", "Zeile \u00E4\u00F6\u00FC"));
        // Darf nicht werfen.
        assertThatCode(() -> decodeStrictUtf8(txt)).doesNotThrowAnyException();
    }

    @Test
    void producesExactExpectedOutputForSample() throws Exception {
        String text = generateText("Rechnung September", "Bitte pruefen", "Zeile 1\nZeile 2\n\nZeile 4");
        assertThat(text).isEqualTo(
                "Rechnung September\r\n"
                        + "Bitte pruefen\r\n"
                        + "\r\n"
                        + "Zeile 1\r\n"
                        + "Zeile 2\r\n"
                        + "\r\n"
                        + "Zeile 4\r\n");
    }

    @Test
    void titleOnlyProducesSingleTerminatedLine() throws Exception {
        assertThat(generateText("Titel", null, null)).isEqualTo("Titel\r\n");
    }

    @Test
    void subtitleOnlyProducesSingleTerminatedLine() throws Exception {
        assertThat(generateText(null, "Untertitel", "")).isEqualTo("Untertitel\r\n");
    }

    @Test
    void titleAndSubtitleWithoutContentHaveNoTrailingBlankLine() throws Exception {
        assertThat(generateText("Titel", "Untertitel", "  ")).isEqualTo("Titel\r\nUntertitel\r\n");
    }

    @Test
    void contentOnlyHasNoLeadingBlankLine() throws Exception {
        assertThat(generateText(null, null, "Nur Inhalt")).isEqualTo("Nur Inhalt\r\n");
        assertThat(generateText("   ", "", "Nur Inhalt")).isEqualTo("Nur Inhalt\r\n");
    }

    @Test
    void blankLineSeparatesSubtitleFromContentWithoutTitle() throws Exception {
        assertThat(generateText(null, "Untertitel", "Inhalt")).isEqualTo("Untertitel\r\n\r\nInhalt\r\n");
    }

    @Test
    void normalizesLfAndCrLfToCrLf() throws Exception {
        assertThat(generateText(null, null, "a\nb\r\nc")).isEqualTo("a\r\nb\r\nc\r\n");
    }

    @Test
    void normalizesLoneCarriageReturnToCrLf() throws Exception {
        assertThat(generateText(null, null, "a\rb")).isEqualTo("a\r\nb\r\n");
    }

    @Test
    void lineBreaksInsideTitleAreNormalizedToo() throws Exception {
        assertThat(generateText("T1\nT2", "S1\r\nS2", "X")).isEqualTo("T1\r\nT2\r\nS1\r\nS2\r\n\r\nX\r\n");
    }

    @Test
    void containsNoBareLineFeedOrCarriageReturn() throws Exception {
        String text = generateText("A\rB\nC", "D\r\n\rE", "F\n\r\nG\r\r\nH\n");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                assertThat(i).as("LF an Position %d ohne vorheriges CR", i).isPositive();
                assertThat(text.charAt(i - 1)).isEqualTo('\r');
            } else if (c == '\r') {
                assertThat(i + 1).as("CR an Position %d ohne folgendes LF", i).isLessThan(text.length());
                assertThat(text.charAt(i + 1)).isEqualTo('\n');
            }
        }
        assertThat(text).endsWith("\r\n");
    }

    @Test
    void preservesEmptyLinesInsideContent() throws Exception {
        assertThat(generateText(null, null, "a\n\nb")).isEqualTo("a\r\n\r\nb\r\n");
    }

    @Test
    void keepsTabs() throws Exception {
        assertThat(generateText("T\tI", null, "Spalte A\tSpalte B\n1\t2"))
                .isEqualTo("T\tI\r\n\r\nSpalte A\tSpalte B\r\n1\t2\r\n");
    }

    @Test
    void preservesUmlautsSharpSEuroAndEmoji() throws Exception {
        String special = "\u00C4\u00D6\u00DC \u00E4\u00F6\u00FC \u00DF \u20AC \uD83D\uDE00 \u4E2D\u6587";
        String text = generateText(special, special, special);
        assertThat(text).isEqualTo(special + "\r\n" + special + "\r\n\r\n" + special + "\r\n");
    }

    @Test
    void removesControlCharactersAndLoneSurrogates() throws Exception {
        String text = generateText("Titel\u0000X", "Sub\u0008Y\u001B[31m", "Zeile\u0001Eins\uD800\u0007");
        assertThat(text).isEqualTo("TitelX\r\nSubY[31m\r\n\r\nZeileEins\r\n");
    }

    @Test
    void contentOfOnlyControlCharactersCountsAsAbsent() throws Exception {
        assertThat(generateText("Titel", null, "\u0000\u0001")).isEqualTo("Titel\r\n");
    }

    @Test
    void keepsSurroundingWhitespaceOfPresentFields() throws Exception {
        assertThat(generateText("  Titel  ", null, " eingerueckt")).isEqualTo("  Titel  \r\n\r\n eingerueckt\r\n");
    }

    @Test
    void maximumSizedInputIsWrittenCompletely() throws Exception {
        String title = "T".repeat(254) + "\u00E4";
        String subtitle = "S".repeat(254) + "\u00DF";
        StringBuilder content = new StringBuilder();
        int line = 0;
        while (content.length() < 10_000) {
            content.append("Zeile ").append(line++).append(" \u20AC\n");
        }
        content.setLength(10_000);
        String contentValue = content.toString();

        String text = generateText(title, subtitle, contentValue);

        String expectedContent = contentValue.replace("\n", "\r\n") + "\r\n";
        assertThat(text).isEqualTo(title + "\r\n" + subtitle + "\r\n\r\n" + expectedContent);
    }

    @Test
    void generatorIsReusableAndDeterministic() {
        FileContentRequest request = new FileContentRequest("Titel", "Sub", "Inhalt");
        assertThat(generator.generate(request)).isEqualTo(generator.generate(request));
    }
}
