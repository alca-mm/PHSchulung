package de.internal.awareness.web.api.dto;

/**
 * Antwort von {@code GET /api/auth/me}: der Benutzername des ueber den Bearer-Token authentifizierten
 * Admin-Benutzers.
 *
 * <p>Bewusst datensparsam - nur der Benutzername, keine Rollen-/Token-/Sitzungsdetails.</p>
 *
 * @param username Benutzername des aktuell authentifizierten Admin-Benutzers
 */
public record MeResponse(String username) {
}
