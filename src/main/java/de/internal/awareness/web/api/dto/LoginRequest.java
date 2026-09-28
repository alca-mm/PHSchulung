package de.internal.awareness.web.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Anmeldeanfrage des Cross-Origin-Clients (JSON-Body von {@code POST /api/auth/login}).
 *
 * <p>Bean Validation stellt sicher, dass weder Benutzername noch Passwort leer sind; fehlt/leert eines,
 * antwortet der Controller mit {@code 400} und einer generischen {@link ApiError} (ohne den Inhalt zu
 * spiegeln). Das Passwort wird ausschliesslich fluechtig zur Pruefung verwendet und NIE geloggt, NIE
 * gespeichert und NIE in einer Antwort zurueckgegeben.</p>
 *
 * @param username Benutzername des einen internen Admin-Benutzers
 * @param password Passwort im Klartext (nur fluechtig zur Pruefung, nie persistiert/geloggt)
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password) {
}
