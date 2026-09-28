package de.internal.awareness.file;

/**
 * Zentrale CSV-Absicherung gegen CSV-/Spreadsheet-Formula-Injection und gegen fehlerhaftes Quoting.
 *
 * <p><b>Hintergrund:</b> Beim Oeffnen in Excel/LibreOffice kann eine Zelle, deren Wert mit {@code = + - @}
 * beginnt, als Formel interpretiert und ausgefuehrt werden (z. B. {@code =HYPERLINK(...)} oder DDE-Aufrufe wie
 * {@code =cmd|' /C calc'!A0}). Entwertet wird mit einem einfachen Anfuehrungszeichen ({@code '}) direkt vor dem
 * Trigger; die Tabellenkalkulation behandelt die Zelle dann als Text. Als Defense-in-depth gelten auch die
 * Fullwidth-Varianten {@code U+FF1D} (Fullwidth {@code =}), {@code U+FF0B} ({@code +}), {@code U+FF0D}
 * ({@code -}) und {@code U+FF20} ({@code @}) als Trigger, weil manche Programme/Eingabemethoden diese beim
 * Import zu den ASCII-Zeichen normalisieren.</p>
 *
 * <p><b>Regel 1 - Feldanfang:</b> Beginnt der Wert mit {@code = + - @}, einer Fullwidth-Variante, einem Tab,
 * CR oder LF, wird ein {@code '} vorangestellt.</p>
 *
 * <p><b>Regel 2 - jeder interne potenzielle Zellstart:</b> Das RFC-4180-Quoting allein schuetzt NICHT
 * zuverlaessig. Locale-abhaengige Tabellenkalkulationen trennen nicht unbedingt am Komma: Deutsches Excel
 * (Listentrennzeichen {@code ;}) ignoriert beim Doppelklick das Komma und damit auch die Anfuehrungszeichen
 * aller Felder ausser dem ersten einer Zeile; ein {@code ;} oder ein einzelnes CR/LF innerhalb eines
 * gequoteten Feldes beginnt dort eine neue Zelle bzw. Zeile. Der alte Textimport-Assistent bricht Zeilen an
 * CR/LF sogar innerhalb von Anfuehrungszeichen um. Deshalb wird jede Position nach {@code , ; Tab CR LF}
 * wie ein Feldanfang behandelt: unmittelbar folgende {@code "} werden uebersprungen (ein Anfuehrungszeichen
 * nach {@code ;} verhindert die Formel in Excel nicht), und steht danach {@code = + - @} oder eine
 * Fullwidth-Variante, wird direkt davor ein {@code '} eingefuegt. Dieselbe Anfuehrungszeichen-Regel gilt am
 * Feldanfang selbst, denn im CSV steht jedes Feld hinter einem Komma oder Zeilenumbruch (z. B.
 * {@code "=1} -&gt; {@code "'=1}). Leerraum vor einem Trigger wird NICHT entwertet - Excel zeigt z. B.
 * {@code " =1+1"} als Text an.</p>
 *
 * <p>Invariante: Trennt ein Parser die Ausgabe an {@code , ; Tab CR LF} OHNE Beachtung von
 * Anfuehrungszeichen und entfernt fuehrende {@code "} jedes Stuecks, beginnt kein Stueck mit
 * {@code = + - @} oder einer Fullwidth-Variante.</p>
 *
 * <p><b>Bewusster Kompromiss (Sicherheit vor Datentreue):</b> Auch harmlose Werte erhalten ein Apostroph,
 * z. B. die negative Zahl {@code -5} -&gt; {@code '-5} (Text statt Zahl; je nach Programm ist das Apostroph
 * sichtbar), {@code a,-b} -&gt; {@code "a,'-b"} oder eine Aufzaehlung {@code "Liste:\n- Punkt"} -&gt;
 * {@code "Liste:\n'- Punkt"}. Eine zuverlaessige Unterscheidung "harmloser Wert" vs. "Formel" ist ueber alle
 * Tabellenkalkulationen und Locales hinweg nicht moeglich.</p>
 *
 * <p><b>Quoting (RFC 4180):</b> Nach der Entwertung wird ein Feld in doppelte Anfuehrungszeichen gesetzt
 * (innere {@code "} werden verdoppelt), sobald es ein Komma, ein Anfuehrungszeichen, CR, LF, Semikolon oder
 * Tab enthaelt. Programme, die Anfuehrungszeichen beachten (RFC-4180-Parser, LibreOffice, Excel-Import mit
 * passendem Trennzeichen), lesen das Feld so als eine Zelle; fuer alle anderen sorgt Regel 2 dafuer, dass
 * jede dabei entstehende Zelle entwertet ist.</p>
 *
 * <p>Es wird KEIN aktiver Inhalt erzeugt - CSV bleibt ein passiver Textwert. Die Klasse ist zustandslos und
 * threadsicher.</p>
 */
public final class CsvSanitizer {

    private CsvSanitizer() {
    }

    /** Regel 1: Zeichen, die am Feldanfang als Formelstart gelten (inkl. Tab/CR/LF und Fullwidth-Varianten). */
    private static boolean isFormulaTrigger(char c) {
        return isCellStartTrigger(c) || c == '\t' || c == '\r' || c == '\n';
    }

    /** Regel 2: Zeichen, die an einem (internen) Zellstart eine Formel beginnen ({@code = + - @}, Fullwidth). */
    private static boolean isCellStartTrigger(char c) {
        return switch (c) {
            case '=', '+', '-', '@', '\uFF1D', '\uFF0B', '\uFF0D', '\uFF20' -> true;
            default -> false;
        };
    }

    /** Zeichen, nach denen eine locale-abhaengige Tabellenkalkulation eine neue Zelle/Zeile beginnen kann. */
    private static boolean isCellSeparator(char c) {
        return c == ',' || c == ';' || c == '\t' || c == '\r' || c == '\n';
    }

    /**
     * Zeichen, bei denen das Feld gequotet werden muss: die RFC-4180-Zeichen (Komma, Anfuehrungszeichen, CR,
     * LF) plus Semikolon und Tab fuer locale-abhaengige Importe (siehe Klassenbeschreibung).
     */
    private static boolean requiresQuoting(CharSequence value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || isCellSeparator(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Macht einen einzelnen Feldwert fuer CSV sicher: neutralisiert Formel-Injection am Feldanfang (Regel 1)
     * und an jedem internen potenziellen Zellstart (Regel 2) und quotet danach, falls noetig (Komma,
     * Anfuehrungszeichen, CR, LF, Semikolon, Tab). {@code null} und {@code ""} werden zu einem leeren Feld.
     *
     * <p>Beispiele: {@code =1+1} -&gt; {@code '=1+1}; {@code a,b} -&gt; {@code "a,b"};
     * {@code =1,2} -&gt; {@code "'=1,2"}; {@code a;=1+1} -&gt; {@code "a;'=1+1"};
     * {@code y;"=1} -&gt; {@code "y;""'=1"}; {@code -5} -&gt; {@code '-5}.</p>
     */
    public static String sanitizeField(String value) {
        String v = value == null ? "" : value;
        StringBuilder out = new StringBuilder(v.length() + 4);

        // Regel 1: Feldanfang (inkl. Tab/CR/LF). Excel/LibreOffice lesen eine mit ' beginnende Zelle als Text.
        boolean prefixedAtStart = !v.isEmpty() && isFormulaTrigger(v.charAt(0));
        if (prefixedAtStart) {
            out.append('\'');
        }

        // Regel 2: Feldanfang und jede Position nach , ; Tab CR LF sind potenzielle Zellstarts.
        boolean atCellStart = true;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (atCellStart && c != '"') {
                // Fuehrende Anfuehrungszeichen wurden uebersprungen; jetzt entscheidet das erste andere Zeichen.
                if (isCellStartTrigger(c) && !(i == 0 && prefixedAtStart)) {
                    out.append('\'');
                }
                atCellStart = false;
            }
            out.append(c);
            if (isCellSeparator(c)) {
                atCellStart = true;
            }
        }

        if (requiresQuoting(out)) {
            return "\"" + out.toString().replace("\"", "\"\"") + "\"";
        }
        return out.toString();
    }

    /**
     * Baut eine vollstaendige CSV-Zeile (ohne Zeilenende) aus Feldwerten, getrennt durch Komma; jeder Wert wird
     * ueber {@link #sanitizeField} abgesichert. Keine Felder bzw. ein {@code null}-Array ergeben {@code ""}.
     */
    public static String row(String... fields) {
        if (fields == null || fields.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(sanitizeField(fields[i]));
        }
        return sb.toString();
    }
}
