package de.internal.awareness.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentifiziert {@code /api/**}-Anfragen anhand eines opaken Bearer-Tokens
 * ({@code Authorization: Bearer <token>}).
 *
 * <p>Ist ein gueltiger (existierender, nicht abgelaufener) Token vorhanden, wird ein
 * {@link UsernamePasswordAuthenticationToken} mit der Rolle {@code ROLE_ADMIN} in den
 * {@link SecurityContext} gesetzt. Andernfalls bleibt die Anfrage unauthentifiziert und die
 * nachgelagerte Autorisierung/der AuthenticationEntryPoint des API-Chains beantwortet sie mit
 * {@code 401} (KEIN Redirect auf {@code /login}).</p>
 *
 * <p>Der Token wird NIEMALS geloggt. Die Pruefung erfolgt zustandslos ueber {@link ApiTokenService}; es wird
 * keine HTTP-Session erzeugt oder genutzt.</p>
 */
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiTokenService tokenService;

    public BearerTokenAuthenticationFilter(ApiTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Nur setzen, wenn nicht bereits (anderweitig) authentifiziert.
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            extractBearerToken(request).ifPresent(rawToken -> tokenService.validate(rawToken)
                    .ifPresent(username -> {
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(
                                        username, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                        authentication.setDetails(
                                new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }));
        }
        filterChain.doFilter(request, response);
    }

    /** Liest den Rohtoken aus dem {@code Authorization: Bearer <token>}-Header (ohne ihn zu loggen). */
    private static Optional<String> extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
