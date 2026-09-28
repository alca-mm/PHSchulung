package de.internal.awareness.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Reine JUnit-5-Tests (ohne Spring) fuer {@link TrackingLinkPolicy}.
 *
 * <p>Geprueft werden die Sicherheitsregeln der Basis-URL (kein {@code javascript:}/{@code data:}/
 * {@code file:}, kein CRLF, keine Zugangsdaten, HTTPS-Pflicht ausser Loopback) sowie der korrekte
 * Aufbau des Trainingslinks.</p>
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
}
