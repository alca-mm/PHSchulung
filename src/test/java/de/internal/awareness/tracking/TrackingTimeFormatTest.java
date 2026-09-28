package de.internal.awareness.tracking;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-Tests fuer {@link TrackingTimeFormat}: einheitliche Anzeige in {@code Europe/Berlin}, korrekte
 * Sommer-/Winterzeit-Umrechnung und Platzhalter fuer {@code null}.
 */
class TrackingTimeFormatTest {

    private final TrackingTimeFormat format = new TrackingTimeFormat();

    @Test
    void formatsInstantInBerlinSummerTime() {
        // 2026-06-01T10:15:00Z -> Sommerzeit (UTC+2) -> 12:15 Uhr
        String text = format.format(Instant.parse("2026-06-01T10:15:00Z"));
        assertThat(text).isEqualTo("01.06.2026 12:15 Uhr");
    }

    @Test
    void formatsInstantInBerlinWinterTime() {
        // 2026-01-15T10:15:00Z -> Winterzeit (UTC+1) -> 11:15 Uhr
        String text = format.format(Instant.parse("2026-01-15T10:15:00Z"));
        assertThat(text).isEqualTo("15.01.2026 11:15 Uhr");
    }

    @Test
    void nullYieldsDefaultDash() {
        assertThat(format.format(null)).isEqualTo("—");
    }

    @Test
    void nullYieldsCustomFallback() {
        assertThat(format.format(null, "kein Versand")).isEqualTo("kein Versand");
    }

    @Test
    void nonNullIgnoresFallback() {
        assertThat(format.format(Instant.parse("2026-01-15T10:15:00Z"), "x"))
                .isEqualTo("15.01.2026 11:15 Uhr");
    }
}
