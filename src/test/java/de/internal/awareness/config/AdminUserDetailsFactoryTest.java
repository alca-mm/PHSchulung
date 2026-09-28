package de.internal.awareness.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-closed-Verhalten des Admin-Benutzeraufbaus: nur bei gesetztem Benutzernamen UND Passwort entsteht ein
 * Admin-Benutzer (mit gehashtem Passwort, Rolle ADMIN); fehlt eines von beiden, existiert KEIN Benutzer
 * (leerer Passwort/Benutzername wird nicht akzeptiert - der Admin-Bereich bleibt gesperrt).
 */
class AdminUserDetailsFactoryTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private static AppAdminProperties props(String username, String password) {
        AppAdminProperties props = new AppAdminProperties();
        props.setUsername(username);
        props.setPassword(password);
        return props;
    }

    @Test
    void createsAdminWithEncodedPasswordWhenBothConfigured() {
        InMemoryUserDetailsManager manager = AdminUserDetailsFactory.create(props("admin", "s3cret-Test-Pw!"), encoder);

        UserDetails user = manager.loadUserByUsername("admin");
        assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority).contains("ROLE_ADMIN");
        // Passwort ist gehasht (kein Klartext) und verifiziert korrekt.
        assertThat(user.getPassword()).isNotEqualTo("s3cret-Test-Pw!");
        assertThat(encoder.matches("s3cret-Test-Pw!", user.getPassword())).isTrue();
    }

    @Test
    void createsNoUserWhenUsernameBlank() {
        InMemoryUserDetailsManager manager = AdminUserDetailsFactory.create(props("   ", "s3cret-Test-Pw!"), encoder);
        assertThatThrownBy(() -> manager.loadUserByUsername("admin"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void createsNoUserWhenPasswordBlank() {
        InMemoryUserDetailsManager manager = AdminUserDetailsFactory.create(props("admin", ""), encoder);
        assertThatThrownBy(() -> manager.loadUserByUsername("admin"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void createsNoUserWhenBothBlank() {
        InMemoryUserDetailsManager manager = AdminUserDetailsFactory.create(props("", ""), encoder);
        assertThatThrownBy(() -> manager.loadUserByUsername("anyone"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
