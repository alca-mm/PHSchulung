package de.internal.awareness.mail;

import java.util.List;

/**
 * Wird geworfen, wenn ein Versand angefordert wird, obwohl mindestens eine Vorbedingung nicht erfuellt
 * ist (z. B. Testmodus aktiv, SMTP nicht konfiguriert, Absender nicht erlaubt). In diesem Fall wird
 * NICHTS versendet.
 *
 * <p>Die Ausnahme traegt die menschenlesbaren {@code blockers}, damit die Weboberflaeche dem Nutzer die
 * konkreten Gruende anzeigen kann. Die Meldung enthaelt bewusst keine sensiblen Details.</p>
 */
public class SendNotAllowedException extends RuntimeException {

    private final List<String> blockers;

    public SendNotAllowedException(List<String> blockers) {
        super("Versand nicht erlaubt: es sind noch nicht alle Vorbedingungen erfuellt.");
        // Defensive Kopie, damit die Liste nach dem Wurf nicht mehr veraendert werden kann.
        this.blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }

    /** Die konkreten, unerfuellten Vorbedingungen (fuer die Anzeige in der Oberflaeche). */
    public List<String> getBlockers() {
        return blockers;
    }
}
