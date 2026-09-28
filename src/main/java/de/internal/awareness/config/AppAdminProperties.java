package de.internal.awareness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Zugangsdaten des EINEN internen Admin-Benutzers (Prefix {@code app.admin}).
 *
 * <p>Die Werte stammen AUSSCHLIESSLICH aus der externen Konfiguration/Umgebung
 * ({@code APP_ADMIN_USERNAME}/{@code APP_ADMIN_PASSWORD}) - niemals aus Code, Repository, Datenbank oder
 * Templates. Es gibt bewusst KEINE Default-Werte: sind Benutzername oder Passwort leer, wird KEIN
 * Admin-Benutzer angelegt (fail-closed, siehe {@code AdminUserDetailsFactory}), d. h. der Admin-Bereich ist
 * dann nicht zugaenglich. Das Passwort wird nie geloggt, nie persistiert und nie im HTML ausgegeben.</p>
 */
@ConfigurationProperties(prefix = "app.admin")
public class AppAdminProperties {

    /** Admin-Benutzername (leer => kein Admin-Benutzer). */
    private String username = "";

    /** Admin-Passwort im Klartext aus der Umgebung (leer => kein Admin-Benutzer). Wird nie geloggt/persistiert. */
    private String password = "";

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username == null ? "" : username.trim();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        // Passwort NICHT trimmen (Leerzeichen koennten Teil des Secrets sein); nur null -> "" normalisieren.
        this.password = password == null ? "" : password;
    }
}
