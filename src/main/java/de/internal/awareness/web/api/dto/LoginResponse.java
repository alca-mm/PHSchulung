package de.internal.awareness.web.api.dto;

/**
 * Erfolgreiche Anmeldeantwort ({@code 200} von {@code POST /api/auth/login}).
 *
 * <p>Enthaelt den frisch ausgegebenen, undurchsichtigen (opaken) Bearer-Token im KLARTEXT - dies ist die
 * EINZIGE Stelle, an der der Klartext-Token existiert. Serverseitig wird nur sein SHA-256-Hash gespeichert
 * (siehe {@code ApiTokenService}). Der Client sendet ihn anschliessend als
 * {@code Authorization: Bearer <token>}. Es wird bewusst KEIN Passwort und KEIN Hash zurueckgegeben.</p>
 *
 * @param token             opaker Bearer-Token (Klartext, nur hier), traegt selbst keine Daten
 * @param expiresInSeconds  verbleibende Gueltigkeit in Sekunden (absolutes Ablaufdatum serverseitig)
 * @param username          Benutzername des angemeldeten Admin-Benutzers
 */
public record LoginResponse(
        String token,
        long expiresInSeconds,
        String username) {
}
