package de.internal.awareness.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.util.StringUtils;

/**
 * Baut den In-Memory-Benutzerspeicher fuer den EINEN Admin-Benutzer - fail-closed.
 *
 * <p>Nur wenn Benutzername UND Passwort konfiguriert (nicht leer) sind, wird genau ein Admin-Benutzer mit der
 * Rolle {@code ADMIN} angelegt; das Passwort wird dabei mit dem {@link PasswordEncoder} gehasht (kein
 * Klartext im Speicher-Manager, kein Persistieren, kein Loggen). Fehlt eines der beiden, wird ein LEERER
 * Manager zurueckgegeben: es existiert dann kein Benutzer, jede Anmeldung scheitert und der Admin-Bereich
 * bleibt gesperrt (statt versehentlich offen zu sein).</p>
 */
public final class AdminUserDetailsFactory {

    private static final Logger log = LoggerFactory.getLogger(AdminUserDetailsFactory.class);

    private AdminUserDetailsFactory() {
    }

    public static InMemoryUserDetailsManager create(AppAdminProperties properties, PasswordEncoder encoder) {
        String username = properties.getUsername();
        String password = properties.getPassword();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            // Fail-closed: kein Benutzer -> Admin-Bereich gesperrt. Es werden KEINE Werte geloggt.
            log.warn("Kein Admin-Benutzer konfiguriert (APP_ADMIN_USERNAME/APP_ADMIN_PASSWORD fehlen oder sind "
                    + "leer). Der Admin-Bereich ist gesperrt, bis beide gesetzt sind.");
            return new InMemoryUserDetailsManager();
        }
        User admin = (User) User.withUsername(username)
                .password(encoder.encode(password))
                .roles("ADMIN")
                .build();
        log.info("Admin-Benutzer aus der Konfiguration geladen (Rolle ADMIN).");
        return new InMemoryUserDetailsManager(admin);
    }
}
