/*
 * Konfiguration der statischen Admin-Oberflaeche (GitHub Pages).
 *
 * PH_API_BASE ist die Herkunft (Origin) des Backends (Spring Boot), das die JSON-API
 * bereitstellt. Dieser Wert ist OEFFENTLICH und KEIN Geheimnis: Er ist im ausgelieferten
 * Quelltext sichtbar und darf niemals Zugangsdaten, Tokens oder Passwoerter enthalten.
 *
 * Lokale Entwicklung:   'http://localhost:8080' (Standard in dieser Datei, damit lokales
 *                       Serving / IntelliJ ohne weitere Konfiguration funktioniert).
 * Produktivbetrieb:     MUSS auf die echte Backend-Herkunft gesetzt werden,
 *                       z.B. 'https://tracking.example.org'.
 *
 * GitHub-Pages-Deployment (automatisch):
 *  - Der Pages-Workflow UEBERSCHREIBT diese Datei beim Deployment vollstaendig durch eine
 *    einzige Zuweisung, gespeist aus der Repository-Variable BACKEND_BASE_URL.
 *  - Ist BACKEND_BASE_URL nicht gesetzt, schreibt der Workflow einen leeren Wert
 *    (window.PH_API_BASE = ""). In diesem Fall zeigt die Admin-Oberflaeche deutlich
 *    "Backend ist noch nicht konfiguriert." und bleibt bedienbar (keine leere Seite).
 *
 * Regeln:
 *  - Diese Datei enthaelt GENAU EINE Zuweisung an window.PH_API_BASE, damit der Workflow
 *    sie zuverlaessig ueberschreiben kann. Keine weitere Logik hinzufuegen.
 *  - Kein abschliessender Schraegstrich (kein trailing slash).
 *  - Die connect-src-Direktive der Content-Security-Policy in admin/index.html MUSS
 *    dieselbe Backend-Herkunft ebenfalls erlauben, sonst blockiert der Browser die
 *    fetch-Anfragen an das Backend. Der Pages-Workflow passt connect-src passend an.
 *  - Das Backend muss CORS so konfigurieren, dass die Frontend-Herkunft
 *    (https://alca-mm.github.io) erlaubt ist.
 */
window.PH_API_BASE = 'http://localhost:8080';
