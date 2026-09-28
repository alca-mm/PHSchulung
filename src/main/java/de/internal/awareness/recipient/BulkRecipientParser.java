package de.internal.awareness.recipient;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zerlegt frei eingegebenen Text in einzelne Empfaenger. Jede Zeile ist entweder eine reine
 * E-Mail-Adresse ({@code email@example.invalid}) oder ein Anzeigename mit Adresse in spitzen Klammern
 * ({@code Anzeigename <email@example.invalid>}).
 *
 * <p>Die Gueltigkeit der Adresse wird bewusst ueber genau denselben Bean-Validation-Mechanismus
 * geprueft, der auch die Entity {@link CampaignRecipient} absichert ({@code @NotBlank @Email} auf
 * {@code email}). Der Parser meldet damit niemals eine Adresse als gueltig, die die Datenbank-Entity
 * beim spaeteren Speichern wieder ablehnen wuerde.</p>
 */
@Component
public class BulkRecipientParser {

    /**
     * Erkennt das Format {@code Anzeigename <email>}: ein (moeglicherweise leerer) Namensteil, gefolgt
     * von der Adresse in spitzen Klammern am Zeilenende. Der Klammerinhalt enthaelt selbst keine spitzen
     * Klammern.
     */
    private static final Pattern DISPLAY_NAME_PATTERN = Pattern.compile("^(.*)<([^<>]*)>$");

    private final Validator validator;

    public BulkRecipientParser(Validator validator) {
        this.validator = validator;
    }

    /** Eine geparste Zeile: Adresse und optionaler Anzeigename ({@code displayName} darf {@code null} sein). */
    public record ParsedRecipient(String email, String displayName) {
    }

    /**
     * Ergebnis des Parsens: die gueltigen Empfaenger und die ungueltigen Zeilen (jeweils getrimmter
     * Originalwortlaut), damit der Benutzer sehen kann, was nicht verarbeitet werden konnte.
     */
    public record ParsedBulk(List<ParsedRecipient> valid, List<String> invalidLines) {
    }

    /**
     * Zerlegt den Rohtext zeilenweise (Trennung an {@code \r?\n}). Jede Zeile wird getrimmt; leere bzw.
     * nur aus Whitespace bestehende Zeilen werden uebersprungen. Zeilen, aus denen keine gueltige Adresse
     * gewonnen werden kann, landen (im getrimmten Originalwortlaut) in {@link ParsedBulk#invalidLines()};
     * alle anderen als {@link ParsedRecipient} in {@link ParsedBulk#valid()}.
     */
    public ParsedBulk parse(String rawText) {
        List<ParsedRecipient> valid = new ArrayList<>();
        List<String> invalidLines = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) {
            return new ParsedBulk(valid, invalidLines);
        }

        for (String rawLine : rawText.split("\r?\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            String email;
            String displayName = null;
            Matcher matcher = DISPLAY_NAME_PATTERN.matcher(line);
            if (matcher.matches()) {
                String name = matcher.group(1).trim();
                displayName = name.isEmpty() ? null : name;
                email = matcher.group(2).trim();
            } else {
                email = line;
            }

            if (isValidEmail(email)) {
                valid.add(new ParsedRecipient(email, displayName));
            } else {
                invalidLines.add(line);
            }
        }
        return new ParsedBulk(valid, invalidLines);
    }

    /**
     * Prueft die Adresse exakt so streng wie die Entity {@link CampaignRecipient} beim Speichern
     * ({@code @NotBlank @Email}). Gueltig genau dann, wenn Bean Validation fuer das Feld {@code email}
     * keine Verletzung meldet.
     */
    private boolean isValidEmail(String candidateEmail) {
        Set<ConstraintViolation<CampaignRecipient>> violations =
                validator.validateValue(CampaignRecipient.class, "email", candidateEmail);
        return violations.isEmpty();
    }
}
