package de.internal.awareness.recipient;

/**
 * Fachlicher Fehler: Es konnte auch nach mehreren Versuchen keine eindeutige Tracking-Identitaet
 * erzeugt werden. Praktisch unerreichbar (256-Bit-Zufallstoken, SHA-256), aber die Retry-Schleife
 * ist bewusst begrenzt, damit es niemals zu einer Endlosschleife kommt.
 */
public class TrackingTokenCollisionException extends RuntimeException {

    public TrackingTokenCollisionException(int attempts) {
        super("Konnte nach " + attempts + " Versuchen keine eindeutige Tracking-Identitaet erzeugen");
    }
}
