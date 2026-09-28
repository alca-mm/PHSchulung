package de.internal.awareness.tracking;

/**
 * Erzeugt neue Tracking-Identitaeten. Bewusst als schmale Abstraktion ueber {@link TrackingTokens},
 * damit die Kollisions-/Retry-Behandlung in Services deterministisch testbar ist (Test kann eine
 * Implementierung einspeisen, die gezielt einen kollidierenden Hash liefert). Die Standard-
 * Implementierung {@link DefaultTrackingTokenFactory} delegiert an {@link TrackingTokens#generate()}.
 */
@FunctionalInterface
public interface TrackingTokenFactory {

    /** Ein neu erzeugtes Token samt speicherbarem SHA-256-Hash. */
    TrackingTokens.GeneratedToken newToken();
}
