package de.internal.awareness.web.api;

import de.internal.awareness.api.ApiTokenService;
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
 *       ohne zu verraten, welcher Teil falsch war, und ohne das Passwort zu spiegeln.</li>
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

    public AuthController(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder,
                          ApiTokenService tokenService) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    /**
     * Meldet den einen internen Admin-Benutzer an und gibt bei Erfolg einen Bearer-Token aus.
     *
     * <p>Fehlerfall bewusst generisch: unbekannter Benutzer, deaktiviertes/gesperrtes Konto und falsches
     * Passwort fuehren zur IDENTISCHEN {@code 401}-Antwort - so wird nicht verraten, welcher Teil falsch war.</p>
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        try {
            UserDetails user = userDetailsService.loadUserByUsername(request.username());
            if (user != null
                    && user.isEnabled()
                    && user.isAccountNonLocked()
                    && user.isAccountNonExpired()
                    && user.isCredentialsNonExpired()
                    && passwordEncoder.matches(request.password(), user.getPassword())) {
                ApiTokenService.IssuedToken issued = tokenService.issue(user.getUsername());
                return ResponseEntity.ok(
                        new LoginResponse(issued.token(), issued.expiresInSeconds(), issued.username()));
            }
        } catch (UsernameNotFoundException ignored) {
            // Bewusst geschluckt: identische generische Fehlerantwort wie bei falschem Passwort.
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("invalid_credentials", "Benutzername oder Passwort ist ungueltig."));
    }

    /** Widerruft den vorgelegten Bearer-Token (Logout). Immer {@code 204}, auch bei fehlendem/unbekanntem Token. */
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
                .body(new ApiError("invalid_request", "Ungueltiger oder fehlender Anfrage-Body."));
    }

    private static Optional<String> extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
