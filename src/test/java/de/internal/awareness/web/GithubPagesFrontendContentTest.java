package de.internal.awareness.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reiner Unit-Test (ohne Spring-Kontext), der die statischen GitHub-Pages-Frontend-Dateien
 * im Arbeitsverzeichnis des Moduls (Projektwurzel) inhaltlich prueft.
 *
 * <p>Geprueft werden die verbindlichen Sicherheits- und Struktur-Regeln der Admin-Oberflaeche:
 * keine clientseitige Authentifizierung / keine fest codierten Zugangsdaten, keine Persistenz
 * des Tokens im Browser-Speicher, keine gefaehrlichen DOM-Sinks (innerHTML/eval), keine externen
 * Ressourcen (Zero-External-Refs), eine restriktive Content-Security-Policy, eine saubere
 * oeffentliche Seite mit Admin-Verweis, eine reine Weiterleitungsseite (login.html) und das
 * Laden der Daten ausschliesslich ueber die assets/*.js-Skripte.</p>
 */
class GithubPagesFrontendContentTest {

    private static final String INDEX = "index.html";
    private static final String LOGIN = "login.html";
    private static final String ADMIN = "admin/index.html";
    private static final String CONFIG_JS = "assets/config.js";
    private static final String API_JS = "assets/api.js";
    private static final String APP_JS = "assets/app.js";
    private static final String ADMIN_CSS = "assets/styles.css";

    // ---- Datei-Helfer -------------------------------------------------------

    private static String read(String relativePath) throws IOException {
        Path path = Path.of(relativePath);
        assertThat(Files.exists(path))
                .as("Frontend-Datei muss existieren: %s", relativePath)
                .isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Entfernt die (einzeiligen) CSP-Meta-Zeilen, in denen Herkuenfte (http/https) zulaessig sind. */
    private static String stripCspLines(String content) {
        return Arrays.stream(content.split("\\R"))
                .filter(line -> !line.contains("Content-Security-Policy"))
                .collect(Collectors.joining("\n"));
    }

    private static boolean containsRegex(String content, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(content);
        return matcher.find();
    }

    private static void assertNoRegex(String content, String regex, String description) {
        assertThat(containsRegex(content, regex))
                .as("Verboten (%s): Muster /%s/ darf nicht vorkommen", description, regex)
                .isFalse();
    }

    // ---- Tests --------------------------------------------------------------

    @Test
    void allExpectedFrontendFilesExist() throws IOException {
        for (String file : new String[]{INDEX, LOGIN, ADMIN, CONFIG_JS, API_JS, APP_JS, ADMIN_CSS}) {
            assertThat(read(file).trim())
                    .as("Datei darf nicht leer sein: %s", file)
                    .isNotEmpty();
        }
    }

    @Test
    void noHardcodedCredentialsOrClientSideAuth() throws IOException {
        String js = read(API_JS) + "\n" + read(APP_JS);

        // Kein clientseitiger Passwortvergleich.
        assertNoRegex(js, "(?i)password\\s*===", "clientseitiger Passwortvergleich (password ===)");
        assertNoRegex(js, "(?i)===\\s*password", "clientseitiger Passwortvergleich (=== password)");
        assertNoRegex(js, "(?i)password\\s*==[^=]", "clientseitiger Passwortvergleich (loses ==)");
        assertNoRegex(js, "(?i)if\\s*\\(\\s*password", "clientseitige Passwortpruefung (if (password ...))");

        // Keine fest codierten Zugangsdaten (Zuweisung eines String-Literals).
        assertNoRegex(js, "(?i)(password|passwort|pwd|benutzername|username)\\s*=\\s*[\"'][^\"']+[\"']",
                "fest codierte Zugangsdaten");

        // Zusaetzlich: keine Zeile enthaelt gleichzeitig 'password' und '==='.
        boolean anyLineWithBoth = Arrays.stream(js.split("\\R"))
                .anyMatch(line -> line.toLowerCase().contains("password") && line.contains("==="));
        assertThat(anyLineWithBoth)
                .as("Keine Zeile darf gleichzeitig 'password' und '===' enthalten")
                .isFalse();
    }

    @Test
    void tokenNeverPersistedInBrowserStorage() throws IOException {
        for (String file : new String[]{CONFIG_JS, API_JS, APP_JS}) {
            String js = read(file);
            assertThat(js).doesNotContainIgnoringCase("localStorage");
            assertThat(js).doesNotContainIgnoringCase("sessionStorage");
            assertThat(js).doesNotContainIgnoringCase("indexedDB");
            assertThat(js).doesNotContainIgnoringCase("document.cookie");
        }
    }

    @Test
    void noDangerousDomSinks() throws IOException {
        String js = read(API_JS) + "\n" + read(APP_JS);
        assertThat(js).doesNotContain("innerHTML");
        assertThat(js).doesNotContain("outerHTML");
        assertThat(js).doesNotContain("insertAdjacentHTML");
        assertThat(js).doesNotContain("document.write");
        assertThat(js).doesNotContain("eval(");
        assertThat(js).doesNotContain("new Function(");
    }

    @Test
    void noExternalResourcesInMarkupAndCss() throws IOException {
        // HTML- und CSS-Dateien: keine Remote-Referenzen (Ausnahme: die CSP-Meta-Zeilen).
        for (String file : new String[]{INDEX, LOGIN, ADMIN, ADMIN_CSS}) {
            String content = read(file);
            String stripped = stripCspLines(content);

            assertThat(stripped)
                    .as("%s darf ausserhalb der CSP-Zeile kein http:// enthalten", file)
                    .doesNotContain("http://");
            assertThat(stripped)
                    .as("%s darf ausserhalb der CSP-Zeile kein https:// enthalten", file)
                    .doesNotContain("https://");

            assertThat(content).as("%s: kein @import", file).doesNotContain("@import");
            assertThat(content).as("%s: keine Google-Fonts", file).doesNotContainIgnoringCase("fonts.");
            assertThat(content).as("%s: keine googleapis", file).doesNotContainIgnoringCase("googleapis");
            assertThat(content).as("%s: kein gstatic", file).doesNotContainIgnoringCase("gstatic");
            assertThat(content).as("%s: kein gtag", file).doesNotContainIgnoringCase("gtag");
            assertThat(content).as("%s: kein analytics", file).doesNotContainIgnoringCase("analytics");
            assertThat(content).as("%s: kein CDN", file).doesNotContainIgnoringCase("cdn.");

            assertNoRegex(content, "(?i)<script[^>]*src\\s*=\\s*[\"']https?:", "externes Skript in " + file);
            assertNoRegex(content, "(?i)<link[^>]*href\\s*=\\s*[\"']https?:", "externe verlinkte Ressource in " + file);
            assertNoRegex(content, "(?i)url\\(\\s*[\"']?https?:", "externe URL in CSS von " + file);
        }

        // JS-Logik (ohne config.js): keine hartkodierten Remote-URLs.
        for (String file : new String[]{API_JS, APP_JS}) {
            String js = read(file);
            assertThat(js).as("%s: kein http://", file).doesNotContain("http://");
            assertThat(js).as("%s: kein https://", file).doesNotContain("https://");
        }
    }

    @Test
    void cspForbidsExternalContent() throws IOException {
        for (String file : new String[]{INDEX, LOGIN, ADMIN}) {
            String content = read(file);
            assertThat(content).as("%s: CSP vorhanden", file).contains("Content-Security-Policy");
            assertThat(content).as("%s: default-src 'self'", file).contains("default-src 'self'");
            assertThat(content).as("%s: base-uri 'none'", file).contains("base-uri 'none'");
        }
        // Admin-SPA: connect-src erlaubt Backend-Herkunft und die GitHub-Pages-Herkunft.
        String admin = read(ADMIN);
        assertThat(admin).contains("connect-src 'self' https://alca-mm.github.io");
        assertThat(admin).contains("script-src 'self'");
    }

    @Test
    void publicIndexIsCleanAndLinksToAdmin() throws IOException {
        String index = read(INDEX);
        String lower = index.toLowerCase();

        assertThat(index).as("Admin-Verweis auf login.html").contains("login.html");
        assertThat(index).as("sichtbarer Admin-Link").contains(">Admin<");

        assertThat(lower).as("kein Token").doesNotContain("token");
        assertThat(index).as("keine API-Basis").doesNotContain("PH_API_BASE");
        assertThat(lower).as("kein Authorization-Header").doesNotContain("authorization");
        assertThat(lower).as("kein Bearer").doesNotContain("bearer");
        assertThat(index).as("keine KPI-Daten").doesNotContain("KPI");
        assertThat(lower).as("kein Passwort").doesNotContain("password");
        assertThat(lower).as("kein Formular").doesNotContain("<form");

        assertNoRegex(index, "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "E-Mail-Adresse in index.html");
    }

    @Test
    void loginHtmlIsRedirectOnly() throws IOException {
        String login = read(LOGIN);
        String lower = login.toLowerCase();

        assertThat(login).as("Weiterleitungsziel").contains("admin/index.html");
        boolean hasRedirect = lower.contains("http-equiv=\"refresh\"") || lower.contains("location.replace");
        assertThat(hasRedirect).as("Weiterleitung per meta-refresh oder location.replace").isTrue();

        assertThat(lower).as("kein Passwortfeld").doesNotContain("password");
        assertThat(lower).as("kein Benutzernamensfeld").doesNotContain("username");
        assertThat(lower).as("kein Eingabefeld").doesNotContain("<input");
        assertThat(lower).as("kein Formular").doesNotContain("<form");
        assertThat(login).as("keine API-Basis").doesNotContain("PH_API_BASE");
        assertThat(lower).as("kein Token").doesNotContain("token");
        assertThat(lower).as("kein Bearer").doesNotContain("bearer");
    }

    @Test
    void adminLoadsDataViaAssetScripts() throws IOException {
        String admin = read(ADMIN);

        // WICHTIG (Root-Cause-Fix): admin/index.html liegt unter /PHSchulung/admin/. Die Assets liegen unter
        // /PHSchulung/assets/. Daher MUSS relativ mit "../assets/..." referenziert werden. Ein bloss relatives
        // "assets/..." wuerde /PHSchulung/admin/assets/... anfragen (HTTP 404) -> leere Seite.
        assertThat(admin).contains("<script src=\"../assets/config.js\"");
        assertThat(admin).contains("<script src=\"../assets/api.js\"");
        assertThat(admin).contains("<script src=\"../assets/app.js\"");
        assertThat(admin).contains("href=\"../assets/styles.css\"");
        // Regressionsschutz gegen den urspruenglichen Fehler: KEIN Verweis ohne "../".
        assertThat(admin).as("kein subpath-falscher src=\"assets/").doesNotContain("src=\"assets/");
        assertThat(admin).as("kein subpath-falscher href=\"assets/").doesNotContain("href=\"assets/");

        // Keine eingebettete Konfiguration/Logik/Daten in der HTML-Datei selbst.
        assertThat(admin).as("PH_API_BASE nur in config.js").doesNotContain("PH_API_BASE");
        assertThat(admin).as("kein innerHTML").doesNotContain("innerHTML");
        assertThat(admin).as("kein eingebettetes JSON").doesNotContainIgnoringCase("application/json");

        // Jedes <script> muss ein externes src besitzen (kein Inline-Skript).
        assertNoRegex(admin, "<script(?![^>]*\\ssrc=)", "Inline-Skript in admin/index.html");
    }

    @Test
    void adminShowsImmediateVisibleBootStateSoPageIsNeverEmpty() throws IOException {
        String admin = read(ADMIN);
        // Statischer, sofort sichtbarer Boot-Zustand im #app-Container (bleibt auch stehen, falls JS scheitert).
        assertThat(admin).as("sichtbarer Boot-Text").containsIgnoringCase("wird geladen");
        assertThat(admin).as("Boot-Container in #app").contains("id=\"boot\"");
        // Verbindungsanzeige und Aktualisieren-Button in der Kopfzeile vorhanden.
        assertThat(admin).contains("id=\"conn-status\"");
        assertThat(admin).contains("id=\"refresh-btn\"");
    }

    @Test
    void appHandlesConfigMixedContentRateLimitAndRetryStates() throws IOException {
        String app = read(APP_JS);
        // Sichtbare Zustaende / Fehlerbehandlung, die eine leere Seite verhindern bzw. Zustaende erklaeren.
        assertThat(app).as("Konfig-/Mixed-Content-Pruefung").contains("configWarning");
        assertThat(app).as("Mixed-Content-Hinweis").containsIgnoringCase("nicht sicher erreicht");
        assertThat(app).as("429-Behandlung (Login)").contains("429");
        assertThat(app).as("Retry bei Netzwerkfehler").containsIgnoringCase("Erneut versuchen");
        assertThat(app).as("Verbindungsanzeige").containsIgnoringCase("Verbunden");
        // Europe/Berlin-Zeitformatierung, keine hartkodierten UTC-Offsets.
        assertThat(app).contains("Europe/Berlin");
    }

    @Test
    void configDefinesApiBaseWithoutSecrets() throws IOException {
        String config = read(CONFIG_JS);
        assertThat(config).contains("window.PH_API_BASE");
        assertThat(config).as("lokaler Standardwert").contains("http://localhost:8080");
        assertThat(config).as("kein abschliessender Schraegstrich").doesNotContain("localhost:8080/");

        String lower = config.toLowerCase();
        assertThat(lower).as("kein Passwort in der Konfiguration").doesNotContain("password");
        assertThat(lower).as("kein 'secret' in der Konfiguration").doesNotContain("secret");
        assertThat(config).as("kein Bearer-Token in der Konfiguration").doesNotContain("Bearer ");
    }
}
