package de.internal.awareness.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Admin-Zugriffsschutz mit Spring Security (SecurityFilterChain-API, keine veraltete Adapter-Klasse).
 *
 * <p>Sicherheitsmodell: {@code anyRequest().authenticated()} mit gezielten {@code permitAll}-Ausnahmen -
 * neue Controller sind damit standardmaessig geschuetzt (nicht versehentlich oeffentlich). Explizit
 * oeffentlich sind nur der Trainingslink-Endpoint {@code GET /t/{token}} (samt seiner serverseitigen
 * Trainings-/Fehlerseite), die Loginseite und die Fehlerseite.</p>
 *
 * <p>Fail-closed: Der Admin-Benutzer wird nur bei gesetzten Zugangsdaten angelegt (siehe
 * {@link AdminUserDetailsFactory}); ohne Konfiguration existiert kein Benutzer und der Admin-Bereich bleibt
 * gesperrt. CSRF bleibt aktiv (Default), Session-Authentifizierung serverseitig, sinnvolle Security-Header.
 * Nur in Servlet-Webkontexten aktiv, damit reine Service-/Persistenz-Tests (webEnvironment=NONE) unberuehrt
 * bleiben.</p>
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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
}
