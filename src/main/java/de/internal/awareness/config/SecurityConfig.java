package de.internal.awareness.config;

import de.internal.awareness.api.ApiTokenService;
import de.internal.awareness.api.BearerTokenAuthenticationFilter;
import de.internal.awareness.web.api.dto.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Zwei getrennte Spring-Security-Filterketten (Multiple-Chain-Muster):
 *
 * <ol>
 *   <li><b>API-Chain</b> ({@code @Order(1)}, {@code securityMatcher("/api/**")}): zustandsloser, Cookie-freier
 *       JSON-Slice mit OPAKER Bearer-Token-Authentifizierung. CSRF ist hier - und NUR hier - deaktiviert
 *       (Begruendung siehe {@link #apiSecurityChain}). Strikte Exakt-Origin-CORS-Allowlist ohne Credentials.
 *       Kein Redirect auf {@code /login}, sondern {@code 401}/{@code 403} als kleines JSON ({@link ApiError}).</li>
 *   <li><b>Web-Chain</b> ({@code @Order(2)}, kein securityMatcher =&gt; alles Uebrige): die BESTEHENDE
 *       Thymeleaf-Absicherung UNVERAENDERT - Formular-Login, Security-Header/CSP, aktives CSRF,
 *       {@code permitAll} fuer {@code /t/*},{@code /login},{@code /error}, {@code anyRequest().authenticated()}.</li>
 * </ol>
 *
 * <p>Warum Bearer-Token statt Cross-Site-Cookie (Variante B): Third-Party-Cookies
 * (github.io &lt;-&gt; Backend-Origin) sind in modernen Browsern unzuverlaessig. Ein opaker Bearer-Token haelt
 * den {@code /api}-Slice zustandslos und CSRF-immun (keine ambienten Credentials) und erlaubt eine strikte
 * Exakt-Origin-CORS-Allowlist OHNE {@code allowCredentials}.</p>
 *
 * <p>Fail-closed: Der Admin-Benutzer wird nur bei gesetzten Zugangsdaten angelegt (siehe
 * {@link AdminUserDetailsFactory}); ohne Konfiguration existiert kein Benutzer, weder Web- noch API-Login sind
 * moeglich. Nur in Servlet-Webkontexten aktiv, damit reine Service-/Persistenz-Tests unberuehrt bleiben.</p>
 */
@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    /** BCrypt zum Hashen des konfigurierten Admin-Passworts (kein Klartext im Benutzerspeicher). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Genau ein Admin-Benutzer aus der Konfiguration (fail-closed). Ein eigener Bean unterdrueckt zudem den
     * von Spring Boot sonst generierten Default-Benutzer samt Zufallspasswort.
     */
    @Bean
    public UserDetailsService userDetailsService(AppAdminProperties adminProperties, PasswordEncoder encoder) {
        return AdminUserDetailsFactory.create(adminProperties, encoder);
    }

    /**
     * API-Chain fuer {@code /api/**}: zustandslos, Bearer-Token, strikte CORS-Allowlist.
     *
     * <p>CSRF ist hier bewusst deaktiviert und NUR hier: Der Slice ist zustandslos und nutzt KEINE Cookies bzw.
     * ambienten Credentials - die Authentifizierung erfolgt ausschliesslich ueber einen expliziten
     * {@code Authorization: Bearer}-Header, den ein fremder Ursprung nicht "ambient" mitsenden kann. Damit gibt
     * es keinen CSRF-Vektor. Der Web-Chain ({@link #webSecurityChain}) behaelt CSRF unveraendert aktiv.</p>
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityChain(HttpSecurity http, ApiTokenService apiTokenService,
                                                CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .securityMatcher("/api/**")
                // CORS aus dem CorsConfigurationSource-Bean (strikte Exakt-Origin-Allowlist, keine Credentials).
                .cors(Customizer.withDefaults())
                // CSRF NUR fuer /api/** deaktiviert (zustandslos, keine Cookies/ambienten Credentials).
                .csrf(csrf -> csrf.disable())
                // Keine HTTP-Session: jede Anfrage authentifiziert sich selbst per Bearer-Token.
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Antworten des JSON-/Auth-Slice duerfen nicht zwischengespeichert werden: Cache-Control:
                // no-store (kein Caching von Tokens/JSON durch Browser/Proxies). Der Web-Chain ist davon
                // unberuehrt.
                .headers(headers -> headers.cacheControl(Customizer.withDefaults()))
                .authorizeHttpRequests(auth -> auth
                        // CORS-Preflight ohne Auth zulassen.
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        // Anmeldung ist der einzige oeffentliche API-Endpoint (liefert erst den Token).
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // Alles Uebrige unter /api/** erfordert den Admin (ROLE_ADMIN via Bearer-Token).
                        .anyRequest().hasRole("ADMIN"))
                // 401/403 als kleines JSON statt Redirect auf /login (kein Stacktrace, keine Details).
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(apiAuthenticationEntryPoint())
                        .accessDeniedHandler(apiAccessDeniedHandler()))
                // Bearer-Token-Filter vor der Formular-Login-Verarbeitung.
                .addFilterBefore(new BearerTokenAuthenticationFilter(apiTokenService),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Web-Chain (Order 2, kein securityMatcher =&gt; matcht alles, was der API-Chain nicht abgefangen hat):
     * die BESTEHENDE Absicherung der Thymeleaf-Oberflaeche - unveraendert.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain webSecurityChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // Oeffentlich: der empfaengerseitige Trainingslink (GET /t/{token}) und die dabei
                        // gerenderte Trainings-/Fehlerseite; Loginseite; Fehlerseite. Bewusst nur EIN
                        // Pfadsegment ("/t/*", nicht "/t/**"): so fallen Nicht-Endpunkt-Unterpfade (z. B.
                        // "/t/a/b", "/t/") auf anyRequest().authenticated() zurueck und werden nicht anonym
                        // ueber eine generische Fehlerseite bedient.
                        .requestMatchers("/t/*").permitAll()
                        .requestMatchers("/login").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Betriebs-Probes der Plattform (Liveness/Readiness) muessen OHNE Login erreichbar
                        // sein; sie liefern nur {"status":...} und keinerlei sensible Daten (HealthController).
                        .requestMatchers("/health", "/readiness").permitAll()
                        // Alles Uebrige (Verwaltung, Versand, Auswertung, Downloads, ...) erfordert Login.
                        .anyRequest().authenticated())
                // Eigene Thymeleaf-Loginseite; formLogin (POST /login), Fehler -> /login?error.
                .formLogin(form -> form
                        .loginPage("/login")
                        .permitAll())
                // Logout ausschliesslich per POST /logout (CSRF-geschuetzt) -> zurueck zur Loginseite.
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                // CSRF bleibt aktiv (Default) - NICHT global deaktivieren. Thymeleaf-Formulare mit th:action
                // fuegen das CSRF-Token automatisch ein (RequestDataValueProcessor); GET /t/{token} braucht keine Ausnahme.
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        // Bewusst einfache CSP, die mit der bestehenden Thymeleaf-Oberflaeche (inline <style>/<script>)
                        // funktioniert; keine externen Ressourcen. Kein Framing, nur eigene Formularziele.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                                        + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                                        + "base-uri 'self'; form-action 'self'; frame-ancestors 'none'")));
        return http.build();
    }

    /**
     * CORS-Konfiguration fuer {@code /api/**}: exakte Origin-Allowlist aus {@link ApiSecurityProperties}
     * (NIE {@code *}); erlaubte Methoden GET/POST/OPTIONS; erlaubte Header {@code Authorization}/
     * {@code Content-Type}; {@code allowCredentials=false} (der Bearer-Token braucht keine Cookies);
     * {@code maxAge=3600}. Gilt ausschliesslich fuer {@code /api/**}.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(ApiSecurityProperties apiProperties) {
        CorsConfiguration config = new CorsConfiguration();
        // Exakte Origins (Schema+Host+Port), NIE Wildcard. Leere Liste => keine fremde Origin erlaubt.
        config.setAllowedOrigins(List.copyOf(apiProperties.getAllowedOrigins()));
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // Bewusst KEINE Credentials (kein Cookie): passt zur Bearer-Token-Strategie und erlaubt strikte
        // Exakt-Origin-Allowlist, ohne den unsicheren "*"-Fall zu benoetigen.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** 401 (nicht authentifiziert) als kleines JSON - KEIN Redirect auf /login. */
    private AuthenticationEntryPoint apiAuthenticationEntryPoint() {
        return (request, response, authException) ->
                writeError(response, HttpStatus.UNAUTHORIZED,
                        new ApiError("unauthorized", "Authentifizierung erforderlich."));
    }

    /** 403 (authentifiziert, aber nicht berechtigt) als kleines JSON. */
    private AccessDeniedHandler apiAccessDeniedHandler() {
        return (request, response, accessDeniedException) ->
                writeError(response, HttpStatus.FORBIDDEN,
                        new ApiError("forbidden", "Zugriff verweigert."));
    }

    /**
     * Schreibt die kleine {@link ApiError} als JSON. Bewusst OHNE Jackson-Abhaengigkeit (haelt die
     * SecurityConfig unabhaengig von der Jackson-Version/Namespace); das JSON hat nur zwei String-Felder und
     * wird sauber escaped.
     */
    private void writeError(HttpServletResponse response, HttpStatus status, ApiError error) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String json = "{\"error\":\"" + jsonEscape(error.error())
                + "\",\"message\":\"" + jsonEscape(error.message()) + "\"}";
        response.getWriter().write(json);
    }

    /** Minimales JSON-String-Escaping (Anfuehrungszeichen, Backslash, Steuerzeichen). */
    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
