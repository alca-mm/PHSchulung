package de.internal.awareness.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reine Unit-Tests der Startzeit-Pruefung {@link ProductionConfigValidator#validate}. Bewusst OHNE
 * Spring-Kontext: die Methode nimmt alle relevanten Werte als Parameter, daher genuegen einfache Aufrufe.
 *
 * <p>Geprueft wird: eine vollstaendige, sichere Produktionskonfiguration besteht; jede einzelne unsichere/
 * unvollstaendige Variante wird mit {@link IllegalStateException} abgelehnt (fail-fast); und die Meldungen
 * nennen die betroffenen Property-NAMEN, aber NIEMALS Secret-Werte (Admin-/SMTP-Passwort).</p>
 */
class ProductionConfigValidatorTest {

    // Sichere, vollstaendige Beispielwerte. Die "Passwoerter" sind fiktive Testwerte und duerfen in
    // KEINER Fehlermeldung auftauchen.
    private static final String ADMIN_USER = "prod-admin";
    private static final String ADMIN_PW = "Admin-Prod-Geheim-123!";
    private static final String BASE_URL = "https://training.example.invalid";
    private static final String REDIRECT_URL = "https://alca-mm.github.io/PHSchulung/";
    private static final List<String> ORIGINS = List.of("https://alca-mm.github.io");
    private static final String MAIL_HOST = "smtp.example.invalid";
    private static final String MAIL_USER = "mailer@example.invalid";
    private static final String MAIL_PW = "Smtp-Prod-Geheim-XYZ";

    // --- Positivfaelle ----------------------------------------------------------

    @Test
    void passesForCompleteSecureConfigWithLiveSendDisabled() {
        assertThatCode(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, REDIRECT_URL, ORIGINS,
                false, "", "", ""))
                .doesNotThrowAnyException();
    }

    @Test
    void passesWhenLiveSendEnabledWithCompleteMailCredentials() {
        assertThatCode(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, REDIRECT_URL, ORIGINS,
                true, MAIL_HOST, MAIL_USER, MAIL_PW))
                .doesNotThrowAnyException();
    }

    @Test
    void passesWhenLiveSendDisabledEvenWithEmptyMailCredentials() {
        assertThatCode(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, REDIRECT_URL, ORIGINS,
                false, "", "", ""))
                .doesNotThrowAnyException();
    }

    @Test
    void passesWithMultipleExactHttpsOriginsIncludingPort() {
        assertThatCode(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, REDIRECT_URL,
                List.of("https://alca-mm.github.io", "https://app.example.invalid:8443"),
                false, "", "", ""))
                .doesNotThrowAnyException();
    }

    // --- Admin-Zugangsdaten -----------------------------------------------------

    @Test
    void failsWhenAdminUsernameMissing() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                "", ADMIN_PW, BASE_URL, REDIRECT_URL, ORIGINS, false, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.admin.username");
    }

    @Test
    void failsWhenAdminPasswordMissing() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, "  ", BASE_URL, REDIRECT_URL, ORIGINS, false, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.admin.password");
    }

    // --- Tracking-Basis-URL -----------------------------------------------------

    @Test
    void failsWhenTrackingBaseUrlBlank() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, "", REDIRECT_URL, ORIGINS, false, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.tracking.base-url");
    }

    @Test
    void failsWhenTrackingBaseUrlInvalid() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, "not a url", REDIRECT_URL, ORIGINS, false, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.tracking.base-url");
    }

    @Test
    void failsWhenTrackingBaseUrlIsHttpLoopback() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, "http://localhost:8080", REDIRECT_URL, ORIGINS, false, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.tracking.base-url");
    }

    @Test
    void failsWhenTrackingBaseUrlIsHttpsLoopback() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, "https://localhost", REDIRECT_URL, ORIGINS, false, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.tracking.base-url");
    }

    // --- Tracking-Redirect-URL --------------------------------------------------

    @Test
    void passesWhenRedirectUrlBlankBecauseRedirectIsOptional() {
        // Leer ist erlaubt (konsistent mit der Basis-Semantik: kein Redirect -> Server zeigt die Trainingsseite).
        assertThatCode(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, "", ORIGINS, false, "", "", ""))
                .doesNotThrowAnyException();
    }

    @Test
    void failsWhenRedirectUrlSetButInvalidOrNonHttps() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, "http://example.invalid/page", ORIGINS, false, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.tracking.redirect-url");
    }

    @Test
    void failsWhenRedirectUrlSetButHttpsLoopback() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, "https://localhost/PHSchulung/", ORIGINS, false, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.tracking.redirect-url");
    }

    // --- CORS-Allowlist ---------------------------------------------------------

    @Test
    void failsWhenAllowedOriginsEmpty() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, REDIRECT_URL, List.of(), false, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.api.allowed-origins");
    }

    @Test
    void failsWhenAllowedOriginsNull() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, REDIRECT_URL, null, false, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.api.allowed-origins");
    }

    @Test
    void failsWhenAllowedOriginsContainsWildcard() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, REDIRECT_URL, List.of("*"), false, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.api.allowed-origins");
    }

    @Test
    void failsWhenAllowedOriginsContainsHttp() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, REDIRECT_URL,
                List.of("http://example.invalid"), false, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.api.allowed-origins");
    }

    @Test
    void failsWhenAllowedOriginsContainsLoopback() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, REDIRECT_URL,
                List.of("http://localhost:5500"), false, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.api.allowed-origins");
    }

    @Test
    void failsWhenAllowedOriginsContainsOriginWithPath() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, REDIRECT_URL,
                List.of("https://example.invalid/path"), false, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.api.allowed-origins");
    }

    // --- SMTP-Zugangsdaten bei aktivem Echtversand ------------------------------

    @Test
    void failsWhenLiveSendEnabledButMailCredentialsMissing() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, REDIRECT_URL, ORIGINS,
                true, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.mail.live-send-enabled")
                .hasMessageContaining("spring.mail.host");
    }

    @Test
    void failsWhenLiveSendEnabledButMailPasswordMissing() {
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, REDIRECT_URL, ORIGINS,
                true, MAIL_HOST, MAIL_USER, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.mail.password");
    }

    // --- Aggregation + kein Secret-Leak -----------------------------------------

    @Test
    void aggregatesAllProblemsIntoOneMessage() {
        // redirect-url ist optional, wird aber gemeldet, wenn GESETZT und ungueltig (hier http-Loopback).
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                "", "", "", "http://localhost/x", List.of(), true, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.admin.username")
                .hasMessageContaining("app.tracking.base-url")
                .hasMessageContaining("app.tracking.redirect-url")
                .hasMessageContaining("app.api.allowed-origins")
                .hasMessageContaining("app.mail.live-send-enabled");
    }

    @Test
    void messagesNameThePropertyButNeverLeakSecretValues() {
        // Fehler wird durch die leere CORS-Allowlist ausgeloest; Admin- und SMTP-Secrets sind gesetzt und
        // duerfen NICHT in der Meldung erscheinen.
        assertThatThrownBy(() -> ProductionConfigValidator.validate(
                ADMIN_USER, ADMIN_PW, BASE_URL, REDIRECT_URL, List.of(),
                true, MAIL_HOST, MAIL_USER, MAIL_PW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.api.allowed-origins")
                .hasMessageNotContaining(ADMIN_PW)
                .hasMessageNotContaining(MAIL_PW);
    }
}
