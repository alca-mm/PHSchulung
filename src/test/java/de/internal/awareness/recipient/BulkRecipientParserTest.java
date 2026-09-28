package de.internal.awareness.recipient;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reine Unit-Tests fuer {@link BulkRecipientParser} - ohne Spring-Kontext. Der Parser wird direkt mit
 * einem echten Bean-Validation-{@code Validator} konstruiert, damit die E-Mail-Pruefung exakt so streng
 * ist wie an der Entity {@link CampaignRecipient}.
 *
 * <p>Deckt ab: reine E-Mail-Zeile, {@code Name <email>}-Format, Ignorieren leerer Zeilen (Fall #8),
 * Trimmen von Zeilen, Meldung ungueltiger Zeilen (Fall #9) und die korrekte Aufteilung eines gemischten
 * Blocks.</p>
 */
class BulkRecipientParserTest {

    private final BulkRecipientParser parser =
            new BulkRecipientParser(Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void parsesPlainEmailLine() {
        BulkRecipientParser.ParsedBulk result = parser.parse("plain@example.invalid");

        assertThat(result.invalidLines()).isEmpty();
        assertThat(result.valid()).hasSize(1);
        BulkRecipientParser.ParsedRecipient recipient = result.valid().get(0);
        assertThat(recipient.email()).isEqualTo("plain@example.invalid");
        assertThat(recipient.displayName()).isNull();
    }

    @Test
    void parsesDisplayNameWithAngleBrackets() {
        BulkRecipientParser.ParsedBulk result = parser.parse("Max Mustermann <max@example.invalid>");

        assertThat(result.invalidLines()).isEmpty();
        assertThat(result.valid()).hasSize(1);
        BulkRecipientParser.ParsedRecipient recipient = result.valid().get(0);
        assertThat(recipient.email()).isEqualTo("max@example.invalid");
        assertThat(recipient.displayName()).isEqualTo("Max Mustermann");
    }

    // Fall #8: leere bzw. nur aus Whitespace bestehende Zeilen werden uebersprungen.
    @Test
    void ignoresBlankAndWhitespaceOnlyLines() {
        BulkRecipientParser.ParsedBulk result = parser.parse("\n   \n\t\nkeep@example.invalid\n\n");

        assertThat(result.valid())
                .extracting(BulkRecipientParser.ParsedRecipient::email)
                .containsExactly("keep@example.invalid");
        assertThat(result.invalidLines()).isEmpty();
    }

    @Test
    void trimsSurroundingWhitespaceOnLines() {
        BulkRecipientParser.ParsedBulk result = parser.parse("   spaced@example.invalid   ");

        assertThat(result.valid()).hasSize(1);
        assertThat(result.valid().get(0).email()).isEqualTo("spaced@example.invalid");
        assertThat(result.valid().get(0).displayName()).isNull();
    }

    // Fall #9: ungueltige Zeilen landen im Originalwortlaut in invalidLines, nicht in valid.
    @Test
    void reportsInvalidLines() {
        BulkRecipientParser.ParsedBulk result = parser.parse("kein-email\na b@c\n<>");

        assertThat(result.valid()).isEmpty();
        assertThat(result.invalidLines()).containsExactly("kein-email", "a b@c", "<>");
    }

    @Test
    void mixedBlockYieldsCorrectSplit() {
        String raw = "good@example.invalid\n"
                + "Max Mustermann <max@example.invalid>\n"
                + "\n"
                + "kein-email\n"
                + "   ada@example.com   ";

        BulkRecipientParser.ParsedBulk result = parser.parse(raw);

        assertThat(result.valid())
                .extracting(BulkRecipientParser.ParsedRecipient::email)
                .containsExactly("good@example.invalid", "max@example.invalid", "ada@example.com");
        assertThat(result.valid())
                .extracting(BulkRecipientParser.ParsedRecipient::displayName)
                .containsExactly(null, "Max Mustermann", null);
        assertThat(result.invalidLines()).containsExactly("kein-email");
    }
}
