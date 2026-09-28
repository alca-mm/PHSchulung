package de.internal.awareness.api;

import de.internal.awareness.config.ApiSecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Kleine, im Speicher gehaltene, NICHT persistente Anmelde-Drossel (Login-Throttle) fuer
 * {@code POST /api/auth/login}. Schuetzt den EINEN Admin-Login vor Brute-Force, ohne externe
 * Infrastruktur (kein Redis o. ae.) und ohne personenbezogene Daten zu erfassen.
 *
 * <p>Datenschutz/Sicherheitsmodell:</p>
 * <ul>
 *   <li>BEWUSST NICHT IP-basiert: Es werden weder IP-Adressen noch User-Agents erfasst
 *       (Datensparsamkeit). Gezaehlt wird stattdessen je Benutzer.</li>
 *   <li>Als Schluessel dient ausschliesslich der SHA-256-Hash (Hex) des NORMALISIERTEN
 *       Benutzernamens (getrimmt, kleingeschrieben) - EXAKT dieselbe Vorgehensweise wie beim
 *       Token-Speicher. Der Klartext-Benutzername wird NIE gespeichert; dadurch ist der Speicher
 *       nicht fuer User-Enumeration nutzbar.</li>
 *   <li>Zusaetzlich ein GLOBALER Zaehler als Fruehwarnsignal (Defense-in-Depth) gegen verteiltes Ausprobieren
 *       vieler verschiedener Benutzernamen. Dieser Zaehler ist BEWUSST NICHT blockierend: er sperrt keine
 *       Anmeldung, sondern schreibt lediglich eine WARN-Logzeile, wenn die globale Schwelle erreicht wird.
 *       Ein blockierender globaler Zaehler wuerde es einem unauthentifizierten Angreifer erlauben, den EINEN
 *       Admin per verteilter Fehlversuche gezielt auszusperren (Self-DoS); der primaere Schutz ist daher die
 *       Pro-Benutzer-Sperre.</li>
 *   <li>Es werden NIEMALS Benutzernamen, Passwoerter oder Hashes geloggt.</li>
 * </ul>
 *
 * <p>Verfahren: Innerhalb eines rollierenden Zeitfensters ({@code app.api.login-window-seconds})
 * werden Fehlversuche gezaehlt. Erreicht ein Schluessel {@code app.api.login-max-attempts}
 * Fehlversuche, wird er fuer {@code app.api.login-lock-seconds} gesperrt; {@link #isBlocked(String)}
 * liefert dann {@code true} und {@link #retryAfterSeconds(String)} die verbleibenden Sekunden fuer den
 * {@code Retry-After}-Header. Nach Ablauf der Sperre beginnt ein frisches Fenster. Der globale Zaehler
 * arbeitet analog mit {@code app.api.login-global-max-attempts}.</p>
 *
 * <p>Thread-Sicherheit: Die Pro-Benutzer-Zaehler liegen in einer {@link ConcurrentHashMap} und werden
 * atomar via {@link ConcurrentMap#compute} fortgeschrieben; der globale Zaehler ist eine
 * {@link AtomicReference}, die atomar via {@link AtomicReference#updateAndGet} aktualisiert wird.
 * Abgelaufene Eintraege werden opportunistisch entfernt (kein unbegrenztes Wachstum).</p>
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final ApiSecurityProperties properties;
    private final Clock clock;

    /** Pro-Benutzer-Zaehler, Schluessel = SHA-256-Hex des normalisierten Benutzernamens. */
    private final ConcurrentMap<String, AttemptState> perKey = new ConcurrentHashMap<>();

    /** Globaler Zaehler ueber ALLE Benutzer (Defense-in-Depth); {@code null} = noch kein Fehlversuch. */
    private final AtomicReference<AttemptState> global = new AtomicReference<>();

    /** Produktivkonstruktor: nutzt die Systemuhr (UTC). */
    @Autowired
    public LoginAttemptService(ApiSecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Testkonstruktor (paketlokal): erlaubt eine steuerbare Uhr fuer deterministische Ablauf-Tests. */
    LoginAttemptService(ApiSecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Prueft, ob weitere Anmeldeversuche fuer den (normalisierten) Benutzernamen aktuell gesperrt sind -
     * entweder wegen der Pro-Benutzer-Grenze ODER wegen der globalen Grenze.
     *
     * <p>Bewusst UNABHAENGIG davon, ob der Benutzer existiert: Der Aufrufer erhaelt bei Sperre dieselbe
     * Antwort, egal ob es den Benutzer gibt (keine User-Enumeration).</p>
     *
     * <p>Es wird ausschliesslich die PRO-BENUTZER-Sperre geprueft. Der globale Zaehler ist bewusst nicht
     * blockierend (nur Fruehwarn-Logzeile), um einen Self-DoS des einen Admins durch verteilte Fehlversuche
     * zu verhindern.</p>
     */
    public boolean isBlocked(String username) {
        Instant now = Instant.now(clock);
        return isLocked(now, perKey.get(key(username)));
    }

    /**
     * Zaehlt einen fehlgeschlagenen Anmeldeversuch (falsches Passwort ODER unbekannter Benutzer -
     * identisch behandelt). Erreicht der Schluessel bzw. der globale Zaehler die Grenze, wird gesperrt.
     */
    public void recordFailure(String username) {
        Instant now = Instant.now(clock);
        int maxAttempts = properties.getLoginMaxAttempts();
        long windowSeconds = properties.getLoginWindowSeconds();
        long lockSeconds = properties.getLoginLockSeconds();
        int globalMax = properties.getLoginGlobalMaxAttempts();

        perKey.compute(key(username), (k, cur) -> advance(cur, now, maxAttempts, windowSeconds, lockSeconds));
        // Globaler Zaehler NUR als Fruehwarnsignal (nicht blockierend). Beim Erreichen der Schwelle eine
        // WARN-Zeile schreiben - ohne Benutzernamen/Hashes/Passwoerter (Datensparsamkeit).
        AttemptState newGlobal = global.updateAndGet(cur -> advance(cur, now, globalMax, windowSeconds, lockSeconds));
        if (newGlobal != null && newGlobal.failures() == globalMax) {
            log.warn("Erhoehte Anzahl fehlgeschlagener Login-Versuche erreicht ({} im aktuellen Fenster). "
                    + "Hinweis auf moegliches verteiltes Ausprobieren; die Anmeldung wird global NICHT gesperrt.",
                    globalMax);
        }
        purgeExpired(now);
    }

    /**
     * Vermerkt eine erfolgreiche Anmeldung: der Pro-Benutzer-Zaehler wird geloescht. Der GLOBALE Zaehler
     * (Defense-in-Depth ueber ALLE Benutzer) wird durch einen einzelnen Erfolg bewusst NICHT zurueckgesetzt.
     */
    public void recordSuccess(String username) {
        perKey.remove(key(username));
        purgeExpired(Instant.now(clock));
    }

    /**
     * Verbleibende Sperrsekunden fuer den {@code Retry-After}-Header (Maximum aus Pro-Benutzer- und globaler
     * Sperre). Solange gesperrt, ist der Wert &gt;= 1 (aufgerundet); sonst {@code 0}.
     */
    public long retryAfterSeconds(String username) {
        Instant now = Instant.now(clock);
        // Nur die (blockierende) Pro-Benutzer-Sperre bestimmt Retry-After; der globale Zaehler blockiert nicht.
        return remainingLockSeconds(now, perKey.get(key(username)));
    }

    /**
     * Loescht alle im Speicher gehaltenen Zaehler (Pro-Benutzer und global). Nur fuer Tests/Diagnose
     * gedacht - es gibt bewusst KEINEN Endpoint, der dies aufruft.
     */
    public void reset() {
        perKey.clear();
        global.set(null);
    }

    /** Aktuelle Anzahl verfolgter Benutzer-Schluessel - nur fuer Tests/Diagnose. */
    int trackedKeys() {
        return perKey.size();
    }

    // --- interne Logik ---------------------------------------------------------------------------

    /**
     * Berechnet den Folgezustand nach einem Fehlversuch. Ein frisches Fenster beginnt, wenn eine vorherige
     * Sperre abgelaufen ist bzw. (ohne aktive Sperre) das Zeitfenster verstrichen ist. Eine bestehende
     * Sperre wird durch weitere Fehlversuche NICHT verlaengert.
     */
    private static AttemptState advance(AttemptState cur, Instant now, int maxAttempts,
                                        long windowSeconds, long lockSeconds) {
        boolean reset;
        if (cur == null) {
            reset = true;
        } else if (cur.lockedUntil() != null) {
            // Waehrend aktiver Sperre weiterzaehlen (ohne zu verlaengern); nach Sperrende neues Fenster.
            reset = !now.isBefore(cur.lockedUntil());
        } else {
            reset = !now.isBefore(cur.windowStart().plusSeconds(windowSeconds));
        }

        if (reset) {
            int failures = 1;
            Instant lockedUntil = failures >= maxAttempts ? now.plusSeconds(lockSeconds) : null;
            return new AttemptState(failures, now, lockedUntil);
        }

        int failures = cur.failures() + 1;
        Instant lockedUntil = cur.lockedUntil();
        if (lockedUntil == null && failures >= maxAttempts) {
            lockedUntil = now.plusSeconds(lockSeconds);
        }
        return new AttemptState(failures, cur.windowStart(), lockedUntil);
    }

    private static boolean isLocked(Instant now, AttemptState state) {
        return state != null && state.lockedUntil() != null && now.isBefore(state.lockedUntil());
    }

    private static long remainingLockSeconds(Instant now, AttemptState state) {
        if (state == null || state.lockedUntil() == null) {
            return 0L;
        }
        long millis = state.lockedUntil().toEpochMilli() - now.toEpochMilli();
        if (millis <= 0L) {
            return 0L;
        }
        // Aufrunden, damit der Wert solange gesperrt >= 1 bleibt (Retry-After nie 0 bei aktiver Sperre).
        return (millis + 999L) / 1000L;
    }

    /** Entfernt opportunistisch Eintraege, die weder gesperrt noch in einem aktiven Fenster sind. */
    private void purgeExpired(Instant now) {
        long windowSeconds = properties.getLoginWindowSeconds();
        perKey.entrySet().removeIf(entry -> isStale(entry.getValue(), now, windowSeconds));
        AttemptState g = global.get();
        if (g != null && isStale(g, now, windowSeconds)) {
            global.compareAndSet(g, null);
        }
    }

    private static boolean isStale(AttemptState state, Instant now, long windowSeconds) {
        boolean lockActive = state.lockedUntil() != null && now.isBefore(state.lockedUntil());
        boolean windowActive = now.isBefore(state.windowStart().plusSeconds(windowSeconds));
        return !lockActive && !windowActive;
    }

    private static String key(String username) {
        return sha256Hex(normalize(username));
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    /** SHA-256-Hash (Hex) - dieselbe Vorgehensweise wie beim Token- und Tracking-Speicher. */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfuegbar", e);
        }
    }

    /**
     * Unveraenderlicher Zaehlerzustand je Schluessel (bzw. global): Anzahl Fehlversuche, Beginn des aktuellen
     * Zeitfensters und - falls gesperrt - der absolute Zeitpunkt, bis zu dem gesperrt ist ({@code null} =
     * nicht gesperrt). Haelt NIE einen Klartext-Benutzernamen.
     */
    private record AttemptState(int failures, Instant windowStart, Instant lockedUntil) {
    }
}
