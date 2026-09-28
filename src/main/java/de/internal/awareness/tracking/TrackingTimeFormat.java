package de.internal.awareness.tracking;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Zentrale, einheitliche Zeitdarstellung fuer die Admin-Tracking-Oberflaeche.
 *
 * <p>Persistiert werden Zeitpunkte weiterhin als {@link Instant} (UTC); ausschliesslich fuer die ANZEIGE im
 * geschuetzten Admin-Bereich werden sie einheitlich in der Zeitzone {@code Europe/Berlin} formatiert. Diese
 * Bean wird von den Thymeleaf-Templates ueber die Spring-Bean-Referenz {@code ${@trackingTimeFormat.format(...)}}
 * genutzt, damit die Zeitzonen-/Formatlogik an genau einer Stelle liegt (DRY) und testbar bleibt.</p>
 *
 * <p>Die Klasse ist zustandslos und threadsicher ({@link DateTimeFormatter} und {@link ZoneId} sind
 * unveraenderlich). Sie erfasst oder speichert nichts und gibt ausschliesslich einen formatierten Text zurueck
 * (keine Zeitzonen-Migration in der Datenbank).</p>
 */
@Component("trackingTimeFormat")
public class TrackingTimeFormat {

    /** Anzeige-Zeitzone des Admin-Dashboards (bewusst fest verdrahtet, unabhaengig von der Server-Standardzone). */
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    /** Anzeigeformat, z. B. {@code "28.09.2026 09:59 Uhr"}. */
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'Uhr'", Locale.GERMANY);

    /** Standard-Platzhalter fuer einen fehlenden Zeitpunkt. */
    private static final String DEFAULT_FALLBACK = "—"; // Geviertstrich

    /**
     * Formatiert einen Zeitpunkt in {@code Europe/Berlin}. {@code null} ergibt den Standard-Platzhalter
     * ({@code "—"}).
     */
    public String format(Instant instant) {
        return format(instant, DEFAULT_FALLBACK);
    }

    /**
     * Formatiert einen Zeitpunkt in {@code Europe/Berlin}; {@code null} ergibt den angegebenen Ersatztext.
     *
     * @param instant  der Zeitpunkt (darf {@code null} sein)
     * @param fallback Ersatztext fuer {@code null}
     * @return der formatierte Zeitpunkt oder der Ersatztext
     */
    public String format(Instant instant, String fallback) {
        if (instant == null) {
            return fallback;
        }
        return FORMAT.format(instant.atZone(ZONE));
    }
}
