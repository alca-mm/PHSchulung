package de.internal.awareness.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Reine JUnit-5-Tests (ohne Spring) fuer {@link TrackingLinkPolicy}.
 *
 * <p>Geprueft werden die Sicherheitsregeln der Basis-URL (kein {@code javascript:}/{@code data:}/
 * {@code file:}, kein CRLF, keine Zugangsdaten, HTTPS-Pflicht ausser Loopback) sowie der korrekte
 * Aufbau des Trainingslinks.</p>
 *
 * <p><b>Doppelte Schutzwirkung:</b> {@link TrackingLinkPolicy#isAcceptableBaseUrl(String)} validiert
 * nicht nur die in E-Mails eingebettete Tracking-Basis-URL, sondern auch das konfigurierte
 * <em>Redirect-Ziel</em>, auf das der Empfaenger nach dem serverseitigen Tracking bei
 * {@code GET /t/{token}} weitergeleitet wird. Die untenstehenden parametrisierten Matrizen
 * ({@link #redirectZielWirdAbgelehnt} / {@link #redirectZielWirdAkzeptiert}) sichern daher
 * ausdruecklich beide Verwendungen ab und verhindern insbesondere Open-Redirects,
 * {@code javascript:}/{@code data:}/{@code file:}-Schemata, protokoll-relative Ziele,
 * eingebettete Zugangsdaten sowie CR/LF-Header-Injection im Redirect-Location-Header.</p>
 */
class TrackingLinkPolicyTest {

    @Test
    void httpsUrlsWerdenAkzeptiert() {
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("https://training.example.invalid")).isTrue();
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("https://training.example.invalid/path")).isTrue();
    }

    @Test
    void httpLoopbackWirdAkzeptiert() {
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("http://localhost:8080")).isTrue();
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("http://127.0.0.1:8080")).isTrue();
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("http://[::1]:8080")).isTrue();
    }

    @Test
    void httpNichtLoopbackWirdAbgelehnt() {
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("http://training.example.invalid")).isFalse();
    }

    @Test
    void gefaehrlicheSchemataWerdenAbgelehnt() {
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("javascript:alert(1)")).isFalse();
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("data:text/html,x")).isFalse();
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("file:///etc/passwd")).isFalse();
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("ftp://host/x")).isFalse();
    }

    @Test
    void eingebetteteZugangsdatenWerdenAbgelehnt() {
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("https://user:pass@training.example.invalid")).isFalse();
    }

    @Test
    void crlfUndLeerraeumeWerdenAbgelehnt() {
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("https://a.example.invalid\r\nSet-Cookie: x")).isFalse();
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("https://a.example.invalid /x")).isFalse();
    }

    @Test
    void nullUndLeerWerdenAbgelehnt() {
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl(null)).isFalse();
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("")).isFalse();
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("   ")).isFalse();
    }

    @Test
    void fehlendesOderRelativesSchemaWirdAbgelehnt() {
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("/t/abc")).isFalse();
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("training.example.invalid")).isFalse();
    }

    @Test
    void buildTrackingUrlBautLinkKorrekt() {
        assertThat(TrackingLinkPolicy.buildTrackingUrl("https://h.example.invalid/", "abc"))
                .isEqualTo("https://h.example.invalid/t/abc");
        assertThat(TrackingLinkPolicy.buildTrackingUrl("https://h.example.invalid", "abc"))
                .isEqualTo("https://h.example.invalid/t/abc");
    }

    // ------------------------------------------------------------------ Redirect-Ziel-Sicherheitsmatrix

    /**
     * Alle Redirect-Ziele bzw. Basis-URLs, die {@link TrackingLinkPolicy#isAcceptableBaseUrl(String)}
     * gemaess Feature-Spezifikation <b>ablehnen</b> muss. Der zweite Wert ist nur eine sprechende
     * Beschreibung fuer den Testnamen.
     */
    static Stream<Arguments> abzulehnendeRedirectZiele() {
        return Stream.of(
                Arguments.of((String) null, "null"),
                Arguments.of("", "leerer String"),
                Arguments.of("   ", "nur Leerraeume"),
                Arguments.of("javascript:alert(1)", "javascript-Schema"),
                Arguments.of("JavaScript:alert(1)", "javascript-Schema mit gemischter Gross-/Kleinschreibung"),
                Arguments.of("data:text/html,x", "data-Schema"),
                Arguments.of("file:///etc/passwd", "file-Schema"),
                Arguments.of("ftp://h/", "ftp-Schema"),
                Arguments.of("//evil.invalid/x", "protokoll-relativ ohne Schema (Open-Redirect)"),
                Arguments.of("/\\evil.invalid", "schema-relativ mit Backslash (ungueltige URI)"),
                Arguments.of("https://user:pass@h/", "eingebettete Zugangsdaten user:pass@"),
                Arguments.of("https://user@h/", "eingebettete Zugangsdaten user@"),
                Arguments.of("https://h/\n", "abschliessendes echtes LF (Header-Injection)"),
                Arguments.of("https://h/\r\nSet-Cookie: x", "echtes CRLF im Redirect (Header-Injection)"),
                Arguments.of("https://h/\t", "abschliessender Tabulator"),
                Arguments.of("https://h/ ", "abschliessendes Leerzeichen"),
                Arguments.of("https://", "fehlender Host (nur https-Schema)"),
                Arguments.of("http://", "fehlender Host (nur http-Schema)"),
                Arguments.of("http://evil.invalid/", "http auf Nicht-Loopback (kein HTTPS)"),
                Arguments.of("not a url", "Muell mit Leerzeichen"),
                Arguments.of(":::", "Muell ohne Schema (ungueltige URI)"),
                Arguments.of("http:", "http-Schema ohne alles"),
                Arguments.of("training.example.invalid", "Host ohne Schema"),
                Arguments.of("/t/abc", "relativer Pfad ohne Schema")
        );
    }

    /**
     * Alle legitimen Redirect-Ziele bzw. Basis-URLs, die
     * {@link TrackingLinkPolicy#isAcceptableBaseUrl(String)} gemaess Spezifikation <b>akzeptieren</b>
     * muss.
     */
    static Stream<Arguments> zulaessigeRedirectZiele() {
        return Stream.of(
                Arguments.of("https://alca-mm.github.io/PHSchulung/", "oeffentliche GitHub-Pages Landing-Page"),
                Arguments.of("https://training.example.invalid", "https ohne Pfad"),
                Arguments.of("https://h/path?q=1#f", "https mit Pfad, Query und Fragment"),
                Arguments.of("http://localhost:8080/", "http Loopback localhost"),
                Arguments.of("http://127.0.0.1/", "http Loopback IPv4"),
                Arguments.of("http://[::1]/", "http Loopback IPv6"),
                Arguments.of("https://h/%0d%0a", "literales %0d%0a bleibt gueltiger Pfad (kein echtes CRLF)")
        );
    }

    @ParameterizedTest(name = "[{index}] abgelehnt: {1}")
    @MethodSource("abzulehnendeRedirectZiele")
    void redirectZielWirdAbgelehnt(String redirectTarget, String beschreibung) {
        // Diese Matrix schuetzt zugleich die Tracking-Basis-URL (E-Mail-Link) und das
        // Redirect-Ziel fuer GET /t/{token}: kein Open-Redirect, keine gefaehrlichen Schemata,
        // keine Zugangsdaten und keine CR/LF-Header-Injection.
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl(redirectTarget))
                .as("Redirect-Ziel / Basis-URL muss abgelehnt werden: %s", beschreibung)
                .isFalse();
    }

    @ParameterizedTest(name = "[{index}] akzeptiert: {1}")
    @MethodSource("zulaessigeRedirectZiele")
    void redirectZielWirdAkzeptiert(String redirectTarget, String beschreibung) {
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl(redirectTarget))
                .as("Legitimes Redirect-Ziel / Basis-URL muss akzeptiert werden: %s", beschreibung)
                .isTrue();
    }

    @Test
    void literalesProzentCrlfBleibtGueltigerPfadUndWirdAkzeptiert() {
        // Subtiler Fall: "%0d" ist KEIN echtes CR, sondern die literale Zeichenfolge '%','0','d'.
        // Der Roh-String enthaelt somit weder CR/LF noch Leerraum -> die CRLF-Pruefung greift bewusst
        // nicht, und es bleibt eine gueltige https-URL mit prozent-kodiertem Pfad. Im Gegensatz dazu
        // wird ein echtes CR/LF (siehe abzulehnendeRedirectZiele) abgelehnt.
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("https://h/%0d%0a")).isTrue();
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("https://h/%0d%0a\n")).isFalse();
    }

    @Test
    void schemaWirdCaseInsensitiveGeprueft() {
        // https/http duerfen in beliebiger Gross-/Kleinschreibung konfiguriert sein.
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("HTTPS://training.example.invalid/")).isTrue();
        assertThat(TrackingLinkPolicy.isAcceptableBaseUrl("HTTP://localhost:8080/")).isTrue();
    }
}
