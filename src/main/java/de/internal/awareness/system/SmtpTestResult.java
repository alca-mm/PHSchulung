package de.internal.awareness.system;

/**
 * Ergebnis eines SMTP-Verbindungstests: eine sichere Kategorie und eine sanitizte Meldung. Enthaelt
 * bewusst KEINE rohen Ausnahmen/Serverantworten/Credentials.
 *
 * @param outcome die Ergebniskategorie
 * @param message die anzuzeigende, sanitizte Meldung (Standardtext der Kategorie, ggf. mit unkritischem Zusatz)
 */
public record SmtpTestResult(SmtpTestOutcome outcome, String message) {

    public static SmtpTestResult of(SmtpTestOutcome outcome) {
        return new SmtpTestResult(outcome, outcome.message());
    }

    public boolean success() {
        return outcome.success();
    }
}
