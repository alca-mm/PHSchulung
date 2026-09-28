/*
 * Konfiguration der statischen Admin-Oberflaeche (GitHub Pages).
 *
 * PH_API_BASE ist die Herkunft (Origin) des Backends (Spring Boot), das die JSON-API
 * bereitstellt. Dieser Wert ist KEIN Geheimnis: Er ist im ausgelieferten Quelltext
 * oeffentlich sichtbar und darf niemals Zugangsdaten, Tokens oder Passwoerter enthalten.
 *
 * Lokale Entwicklung:   'http://localhost:8080'
 * Produktivbetrieb:     MUSS auf die echte Backend-Herkunft gesetzt werden,
 *                       z.B. 'https://tracking.example.org'.
 *
 * Regeln:
 *  - Kein abschliessender Schraegstrich (kein trailing slash).
 *  - Die connect-src-Direktive der Content-Security-Policy in admin/index.html MUSS
 *    dieselbe Backend-Herkunft ebenfalls erlauben, sonst blockiert der Browser die
 *    fetch-Anfragen an das Backend.
 *  - Das Backend muss CORS so konfigurieren, dass die Frontend-Herkunft
 *    (https://alca-mm.github.io) erlaubt ist.
 */
window.PH_API_BASE = 'http://localhost:8080';
