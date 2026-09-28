package de.internal.awareness.web.api;

import de.internal.awareness.api.ApiTokenService;
import de.internal.awareness.api.LoginAttemptService;
import de.internal.awareness.web.api.dto.ApiError;
import de.internal.awareness.web.api.dto.LoginRequest;
import de.internal.awareness.web.api.dto.LoginResponse;
import de.internal.awareness.web.api.dto.MeResponse;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON-Authentifizierungs-Endpunkte des zustandslosen Cross-Origin-Slice unter {@code /api/auth}.
 *
 * <p>Strategie (Variante B): Die Anmeldung liefert einen kurzlebigen, serverseitig gespeicherten,
 * widerrufbaren OPAKEN Bearer-Token. Es werden bewusst KEINE Cross-Site-Cookies verwendet (in modernen
 * Browsern unzuverlaessig); der Slice bleibt dadurch zustandslos und CSRF-immun (keine ambienten
 * Credentials).</p>
 *
 * <ul>
 *   <li>{@code POST /api/auth/login} - prueft die Zugangsdaten gegen den EINEN konfigurierten Admin-Benutzer
 *       ({@link UserDetailsService} + {@link PasswordEncoder}); bei Erfolg {@code 200} mit
 *       {@link LoginResponse}, sonst GENERISCH {@code 401} ({@link ApiError} {@code invalid_credentials}),
 *       ohne zu verraten, welcher Teil falsch war, und ohne das Passwort zu spiegeln. Eine kleine
 *       In-Memory-Drossel ({@link LoginAttemptService}) beantwortet zu viele Fehlversuche VORAB mit
 *       {@code 429} ({@code too_many_attempts}) samt {@code Retry-After} - identisch, egal ob der Benutzer
 *       existiert (keine User-Enumeration), und BEWUSST NICHT IP-basiert (Datensparsamkeit).</li>
 *   <li>{@code POST /api/auth/logout} - widerruft den vorgelegten Token, {@code 204}.</li>
 *   <li>{@code GET /api/auth/me} - liefert den Benutzernamen des authentifizierten Admins ({@code 200});
 *       ohne gueltigen Token beantwortet der API-Chain die Anfrage mit {@code 401}.</li>
 * </ul>
 *
 * <p>Das Passwort wird NIE geloggt, NIE gespeichert und NIE in einer Antwort ausgegeben.</p>
 *
 * <p>Nur in Servlet-Webkontexten aktiv ({@link ConditionalOnWebApplication}): Der Controller haengt von den
 * Beans {@code UserDetailsService}/{@code PasswordEncoder} ab, die {@code SecurityConfig} ebenfalls nur im
 * Servlet-Web bereitstellt. So bleiben reine Service-/Persistenz-Tests (webEnvironment=NONE) unberuehrt.</p>
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final ApiTokenService tokenService;
    private final LoginAttemptService loginAttemptService;

    /**
     * Beim Start einmalig erzeugter, gueltiger Dummy-Passwort-Hash (gleiche Kostenfunktion wie echte Hashes).
     * Er wird auf allen Fehlpfaden (unbekannter Benutzer / nicht anmeldeberechtigt) fuer einen echten
     * {@code matches()}-Vergleich verwendet, damit die Antwortzeit sich nicht messbar unterscheidet, je nachdem
     * ob der Benutzer existiert (Schutz vor User-Enumeration ueber ein Timing-Seitenkanal).
     */
    private final String dummyPasswordHash;

    public AuthController(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder,
                          ApiTokenService tokenService, LoginAttemptService loginAttemptService) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.loginAttemptService = loginAttemptService;
        // Einmaliger, valider BCrypt-Hash eines Zufallswerts; Klartext wird nirgends benoetigt/gespeichert.
        this.dummyPasswordHash = passwordEncoder.encode("timing-equalization-" + java.util.UUID.randomUUID());
    }

    /**
     * Meldet den einen internen Admin-Benutzer an und gibt bei Erfolg einen Bearer-Token aus.
     *
     * <p>Fehlerfall bewusst generisch: unbekannter Benutzer, deaktiviertes/gesperrtes Konto und falsches
     * Passwort fuehren zur IDENTISCHEN {@code 401}-Antwort - so wird nicht verraten, welcher Teil falsch war.</p>
     *
     * <p>VOR der Zugangsdatenpruefung wird die Anmelde-Drossel konsultiert: Ist der (normalisierte)
     * Benutzername aktuell gesperrt, wird sofort {@code 429} ({@code too_many_attempts}) mit
     * {@code Retry-After} beantwortet - identisch, egal ob der Benutzer existiert (keine User-Enumeration).
     * Fehlversuche werden anschliessend gezaehlt, ein Erfolg setzt den Zaehler zurueck. Fehlende/leere Felder
     * werden bereits durch Bean Validation als {@code 400} abgefangen (siehe {@link #handleValidation}) und
     * gelangen NICHT in die Drossel - blanke Felder zaehlen also nicht als Fehlversuch.</p>
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        String username = request.username();

        // Drossel VOR der Pruefung: identische 429-Antwort unabhaengig von der Existenz des Benutzers.
        if (loginAttemptService.isBlocked(username)) {
            long retryAfterSeconds = loginAttemptService.retryAfterSeconds(username);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
                    .body(new ApiError("too_many_attempts", "Zu viele Anmeldeversuche. Bitte kurz warten."));
        }

        // Timing-Angleichung: In JEDEM Zweig laeuft genau ein BCrypt-Vergleich (echter Hash bei
        // anmeldeberechtigtem Benutzer, sonst der Dummy-Hash), damit die Antwortzeit nicht verraet, ob der
        // Benutzer existiert (keine User-Enumeration ueber Timing).
        UserDetails user;
        try {
            user = userDetailsService.loadUserByUsername(username);
        } catch (UsernameNotFoundException ignored) {
            user = null;
        }
        boolean eligible = user != null
                && user.isEnabled()
                && user.isAccountNonLocked()
                && user.isAccountNonExpired()
                && user.isCredentialsNonExpired();
        String hashToCheck = eligible ? user.getPassword() : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        if (eligible && passwordMatches) {
            loginAttemptService.recordSuccess(username);
            ApiTokenService.IssuedToken issued = tokenService.issue(user.getUsername());
            return ResponseEntity.ok(
                    new LoginResponse(issued.token(), issued.expiresInSeconds(), issued.username()));
        }
        // Fehlgeschlagene Anmeldung (unbekannter Benutzer ODER falsches Passwort) identisch zaehlen.
        loginAttemptService.recordFailure(username);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("invalid_credentials", "Benutzername oder Passwort ist ungültig."));
    }

    /**
     * Widerruft den vorgelegten Bearer-Token (Logout) und liefert {@code 204} (idempotent). Ohne gueltigen Token
     * beantwortet bereits der API-Security-Chain die Anfrage mit {@code 401}; diese Methode wird dann nicht
     * erreicht. Das Frontend behandelt sowohl {@code 204} als auch {@code 401} als "abgemeldet".
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        extractBearerToken(authorizationHeader).ifPresent(tokenService::revoke);
        return ResponseEntity.noContent().build();
    }

    /** Liefert den Benutzernamen des ueber den Bearer-Token authentifizierten Admins. */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(Authentication authentication) {
        return ResponseEntity.ok(new MeResponse(authentication.getName()));
    }

    // --- Fehlerbehandlung (lokal, damit JSON-ApiError statt HTML/ProblemDetail geliefert wird) ---

    /** Fehlende/leere Felder -> {@code 400}, ohne den (moeglicherweise sensiblen) Inhalt zu spiegeln. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ignored) {
        return ResponseEntity.badRequest()
                .body(new ApiError("invalid_request", "Benutzername und Passwort sind erforderlich."));
    }

    /** Fehlender/kaputter JSON-Body -> {@code 400} (kein Stacktrace, kein Body-Echo). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest()
                .body(new ApiError("invalid_request", "Ungültiger oder fehlender Anfrage-Body."));
    }

    private static Optional<String> extractBearerToken(String authorizationHeader) {
        // Das Auth-Schema ist laut RFC 6750 case-insensitiv ("Bearer"/"bearer").
        if (authorizationHeader == null || authorizationHeader.length() < BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Optional.empty();
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
