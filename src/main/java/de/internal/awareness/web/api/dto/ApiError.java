package de.internal.awareness.web.api.dto;

/**
 * Minimale, unspezifische Fehlerantwort fuer den zustandslosen {@code /api/**}-Slice.
 *
 * <p>Bewusst KLEIN gehalten (nur ein maschinenlesbarer {@code error}-Code und eine kurze, generische
 * {@code message}) - es werden NIEMALS interne Details ausgegeben: keine Stacktraces, keine Klartext-
 * Zugangsdaten, keine Tokens/Hashes, keine personenbezogenen Daten. Die Login-Fehlerantwort ist bewusst
 * generisch (siehe {@code AuthController}) und verraet nicht, ob Benutzername oder Passwort falsch war.</p>
 *
 * @param error   stabiler, maschinenlesbarer Fehlercode (z. B. {@code invalid_credentials})
 * @param message kurze, fuer Menschen lesbare, generische Beschreibung ohne sensible Details
 */
public record ApiError(String error, String message) {
}
