package de.internal.awareness.tracking;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Reine Hilfsklasse zur Pruefung der oeffentlichen Tracking-Basis-URL und zum Bau
 * empfaengerspezifischer Trainingslinks der Form {@code {baseUrl}/t/{token}}.
 *
 * <p>Die Klasse ist bewusst PUR gehalten: kein Spring, kein I/O, keine Zustaende. Sie kapselt nur
 * die Sicherheitsregeln fuer die Basis-URL und die deterministische Zusammensetzung des Links.</p>
 *
 * <p><b>Sicherheitsabsicht (fail-closed):</b> Die eingehende Basis-URL stammt aus der Konfiguration
 * und wird spaeter in E-Mails eingebettet. Deshalb werden ausschliesslich sichere URLs akzeptiert:</p>
 * <ul>
 *   <li>Keine gefaehrlichen Schemata wie {@code javascript:}, {@code data:} oder {@code file:} - erlaubt
 *       sind nur {@code http} und {@code https}.</li>
 *   <li>Keine CR-/LF-Zeichen und generell keine ASCII-Leerraeume (Schutz vor Header-Injection und
 *       kaputten Links).</li>
 *   <li>Keine eingebetteten Zugangsdaten (User-Info bzw. {@code @} in der Authority).</li>
 *   <li>{@code https} ist Pflicht; {@code http} ist nur fuer Loopback-Adressen der lokalen Entwicklung
 *       zulaessig.</li>
 * </ul>
 */
public final class TrackingLinkPolicy {

    /** Reine Hilfsklasse: keine Instanzen erlaubt. */
    private TrackingLinkPolicy() {
    }

    /**
     * Prueft, ob eine konfigurierte Basis-URL sicher genug ist, um daraus Trainingslinks zu bauen.
     *
     * <p><b>Fail-closed:</b> Bei jedem Zweifel (null, leer, Parse-Fehler, unerwartete Ausnahme) wird
     * {@code false} zurueckgegeben. Die Pruefung folgt diesen Regeln:</p>
     * <ul>
     *   <li>{@code null} oder leer -> abgelehnt.</li>
     *   <li>Enthaelt CR ({@code \r}), LF ({@code \n}) oder irgendein ASCII-Leerraumzeichen -> abgelehnt
     *       (Schutz vor Header-Injection und kaputten Links).</li>
     *   <li>Nicht als {@link URI} parsbar -> abgelehnt.</li>
     *   <li>Schema fehlt oder ist nicht {@code http}/{@code https} (case-insensitive) -> abgelehnt.
     *       Damit werden {@code javascript:}, {@code data:}, {@code file:}, {@code ftp:} usw. inhaerent
     *       verworfen.</li>
     *   <li>Eingebettete Zugangsdaten (User-Info bzw. {@code @} in der Authority) -> abgelehnt.</li>
     *   <li>Host fehlt oder ist leer -> abgelehnt.</li>
     *   <li>{@code https} -> akzeptiert.</li>
     *   <li>{@code http} -> nur akzeptiert, wenn der Host eine Loopback-Adresse ist.</li>
     * </ul>
     *
     * @param baseUrl die zu pruefende Basis-URL (darf {@code null} sein)
     * @return {@code true} nur fuer eine sichere Basis-URL, sonst {@code false}
     */
    public static boolean isAcceptableBaseUrl(String baseUrl) {
        try {
            if (baseUrl == null || baseUrl.isBlank()) {
                return false;
            }
            // CR/LF und jegliche ASCII-Leerraeume ablehnen (Header-Injection, kaputte Links).
            if (containsCrLfOrAsciiWhitespace(baseUrl)) {
                return false;
            }

            // Aeusseren Rand trimmen; innere Leerraeume sind oben bereits ausgeschlossen.
            String trimmed = baseUrl.trim();

            final URI uri;
            try {
                uri = new URI(trimmed);
            } catch (URISyntaxException ex) {
                return false;
            }

            // Schema muss vorhanden und http/https sein (case-insensitive).
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            String schemeLower = scheme.toLowerCase(Locale.ROOT);
            boolean isHttps = schemeLower.equals("https");
            boolean isHttp = schemeLower.equals("http");
            if (!isHttps && !isHttp) {
                return false;
            }

            // Keine eingebetteten Zugangsdaten zulassen.
            String authority = uri.getAuthority();
            if (uri.getUserInfo() != null || (authority != null && authority.indexOf('@') >= 0)) {
                return false;
            }

            // Host muss vorhanden und nicht leer sein.
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }

            // https ist fuer den echten Versand ausreichend und Pflicht.
            if (isHttps) {
                return true;
            }

            // Fuer echten Versand an externe Empfaenger ist HTTPS erforderlich; http ist nur fuer die
            // lokale Entwicklung (Loopback) erlaubt, und ein Empfaenger ausserhalb des
            // Entwicklungsrechners kann localhost nicht erreichen.
            return isLoopbackHost(host);
        } catch (RuntimeException ex) {
            // Robust bleiben: jede unerwartete Ausnahme fuehrt zu einer Ablehnung (fail-closed).
            return false;
        }
    }

    /**
     * Baut aus einer bereits geprueften Basis-URL und einem Token den empfaengerspezifischen
     * Trainingslink der Form {@code {baseUrl}/t/{token}}.
     *
     * <p><b>Vorbedingung:</b> Der Aufrufer hat die Basis-URL zuvor mit
     * {@link #isAcceptableBaseUrl(String)} validiert, und das Token ist ein URL-sicherer
     * base64url-String. Das Token wird deshalb bewusst NICHT url-kodiert.</p>
     *
     * <p>Beispiel: Basis {@code https://training.example.invalid/} und Token {@code abc} ergeben
     * {@code https://training.example.invalid/t/abc}.</p>
     *
     * @param baseUrl die bereits gepruefte Basis-URL
     * @param token   das URL-sichere base64url-Token des Empfaengers
     * @return der zusammengesetzte Trainingslink
     */
    public static String buildTrackingUrl(String baseUrl, String token) {
        // Etwaige abschliessende Schraegstriche entfernen, damit kein doppeltes "/" entsteht.
        String normalizedBase = baseUrl.replaceAll("/+$", "");
        return normalizedBase + "/t/" + token;
    }

    /**
     * Prueft, ob der String CR, LF oder ein sonstiges ASCII-Leerraumzeichen enthaelt.
     *
     * @param value der zu pruefende String (nie {@code null})
     * @return {@code true}, wenn ein verbotenes Zeichen vorkommt
     */
    private static boolean containsCrLfOrAsciiWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000B') {
                return true;
            }
        }
        return false;
    }

    /**
     * Prueft, ob der Host eine Loopback-Adresse der lokalen Entwicklung ist. Der aus der {@link URI}
     * gelieferte Host fuer IPv6-Loopback ist {@code [::1]}; zur Sicherheit werden beide Schreibweisen
     * ({@code ::1} und {@code [::1]}) akzeptiert.
     *
     * @param host der Host aus der URI (nie {@code null})
     * @return {@code true} fuer {@code localhost}, {@code 127.0.0.1} oder IPv6-Loopback
     */
    private static boolean isLoopbackHost(String host) {
        String hostLower = host.toLowerCase(Locale.ROOT);
        return hostLower.equals("localhost")
                || hostLower.equals("127.0.0.1")
                || hostLower.equals("::1")
                || hostLower.equals("[::1]");
    }
}
