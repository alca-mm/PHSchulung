package de.internal.awareness.config;

import de.internal.awareness.tracking.TrackingLinkPolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Startzeit-Pruefung der Produktionskonfiguration (nur aktiv im Profil {@code prod}).
 *
 * <p><b>Fail-fast/fail-closed:</b> Ist die Konfiguration unsicher oder unvollstaendig, wirft diese Bean
 * beim Hochfahren eine {@link IllegalStateException}. Dadurch verweigert der Spring-Kontext den Start -
 * die Anwendung laeuft in Produktion NIE mit unsicheren Standardwerten (z. B. fehlendem Admin-Login,
 * http-/Loopback-Trackinglinks oder einer offenen CORS-Allowlist) an.</p>
 *
 * <p>Die eigentliche Pruefung liegt in der reinen, seiteneffektfreien Methode
 * {@link #validate(String, String, String, String, List, boolean, String, String, String)}. Sie ist bewusst
 * OHNE Spring-Kontext unit-testbar (nimmt die relevanten Werte direkt als Parameter). Die Bean ruft sie in
 * {@link #afterPropertiesSet()} mit den real gebundenen Konfigurationswerten auf.</p>
 *
 * <p><b>Datenschutz/Secrets:</b> Die Fehlermeldungen nennen ausschliesslich die betroffenen
 * Property-NAMEN, niemals die konkreten Werte (kein Passwort, kein SMTP-Secret im Log/Exception-Text).</p>
 */
@Configuration
@Profile("prod")
public class ProductionConfigValidator implements InitializingBean {

    private final AppAdminProperties adminProperties;
    private final AppTrackingProperties trackingProperties;
    private final ApiSecurityProperties apiSecurityProperties;
    private final AppMailProperties mailProperties;
    private final String mailHost;
    private final String mailUsername;
    private final String mailPassword;

    /**
     * Bindet die vorhandenen Konfigurations-Beans sowie die SMTP-Zugangsdaten aus {@code spring.mail.*}
     * (die nicht in einer eigenen Property-Bean liegen). Die SMTP-Werte werden NUR fuer die Pruefung
     * gelesen und nirgends geloggt oder gespeichert.
     */
    public ProductionConfigValidator(AppAdminProperties adminProperties,
                                     AppTrackingProperties trackingProperties,
                                     ApiSecurityProperties apiSecurityProperties,
                                     AppMailProperties mailProperties,
                                     @Value("${spring.mail.host:}") String mailHost,
                                     @Value("${spring.mail.username:}") String mailUsername,
                                     @Value("${spring.mail.password:}") String mailPassword) {
        this.adminProperties = adminProperties;
        this.trackingProperties = trackingProperties;
        this.apiSecurityProperties = apiSecurityProperties;
        this.mailProperties = mailProperties;
        this.mailHost = mailHost;
        this.mailUsername = mailUsername;
        this.mailPassword = mailPassword;
    }

    /** Fuehrt die Produktionspruefung beim Start aus; wirft bei Problemen und verhindert so den Start. */
    @Override
    public void afterPropertiesSet() {
        validate(
                adminProperties.getUsername(),
                adminProperties.getPassword(),
                trackingProperties.getBaseUrl(),
                trackingProperties.getRedirectUrl(),
                apiSecurityProperties.getAllowedOrigins(),
                mailProperties.isLiveSendEnabled(),
                mailHost,
                mailUsername,
                mailPassword);
    }

    /**
     * Prueft die produktionsrelevante Konfiguration und wirft bei mindestens einem Problem eine
     * {@link IllegalStateException}, deren Meldung ALLE gefundenen Probleme buendelt. Die Meldung nennt nur
     * Property-NAMEN, nie deren Werte.
     *
     * @param adminUsername       {@code app.admin.username} (muss gesetzt sein)
     * @param adminPassword       {@code app.admin.password} (muss gesetzt sein; wird nie in die Meldung geschrieben)
     * @param trackingBaseUrl     {@code app.tracking.base-url} (muss oeffentliche https-URL sein)
     * @param trackingRedirectUrl {@code app.tracking.redirect-url} (muss oeffentliche https-URL sein)
     * @param allowedOrigins      {@code app.api.allowed-origins} (nicht leer, jede Origin exakt https)
     * @param liveSendEnabled     {@code app.mail.live-send-enabled}
     * @param mailHost            {@code spring.mail.host} (nur bei aktivem Echtversand erforderlich)
     * @param mailUsername        {@code spring.mail.username} (nur bei aktivem Echtversand erforderlich)
     * @param mailPassword        {@code spring.mail.password} (nur bei aktivem Echtversand erforderlich; nie in der Meldung)
     * @throws IllegalStateException wenn die Konfiguration unsicher oder unvollstaendig ist
     */
    static void validate(String adminUsername,
                         String adminPassword,
                         String trackingBaseUrl,
                         String trackingRedirectUrl,
                         List<String> allowedOrigins,
                         boolean liveSendEnabled,
                         String mailHost,
                         String mailUsername,
                         String mailPassword) {
        List<String> problems = new ArrayList<>();

        // 1) Admin-Login: kein unsicherer Default-Admin in Produktion.
        if (isBlank(adminUsername) || isBlank(adminPassword)) {
            problems.add("Admin-Zugangsdaten fehlen oder sind leer: app.admin.username und "
                    + "app.admin.password muessen in Produktion gesetzt sein.");
        }

        // 2) Tracking-Basis-URL: gueltige, oeffentliche https-URL (kein http, kein Loopback).
        if (!isSecurePublicHttpsUrl(trackingBaseUrl)) {
            problems.add("app.tracking.base-url muss in Produktion eine gueltige oeffentliche https-URL sein "
                    + "(kein http, kein Loopback, keine eingebetteten Zugangsdaten).");
        }

        // 3) Redirect-URL: OPTIONAL (konsistent mit der Basis-Semantik: leer => es wird nicht umgeleitet,
        //    stattdessen zeigt der Server die Trainingsseite). NUR falls gesetzt, muss sie eine gueltige,
        //    oeffentliche https-URL sein (kein http, kein Loopback). So ueberrascht der Start keinen Operator,
        //    der bewusst ohne Redirect betreibt; ein GESETZTER, aber unsicherer Wert wird weiterhin abgelehnt.
        if (trackingRedirectUrl != null && !trackingRedirectUrl.isBlank()
                && !isSecurePublicHttpsUrl(trackingRedirectUrl)) {
            problems.add("app.tracking.redirect-url ist gesetzt, aber keine gueltige oeffentliche https-URL "
                    + "(kein http, kein Loopback, keine eingebetteten Zugangsdaten). Leer lassen, wenn keine "
                    + "Weiterleitung gewuenscht ist.");
        }

        // 4) CORS-Allowlist: nicht leer und jede Origin exakt https (kein '*', kein Pfad, kein Loopback/http).
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            problems.add("app.api.allowed-origins darf in Produktion nicht leer sein: mindestens eine exakte "
                    + "https-Origin ist erforderlich.");
        } else {
            boolean anyInvalid = false;
            for (String origin : allowedOrigins) {
                if (!isExactHttpsOrigin(origin)) {
                    anyInvalid = true;
                    break;
                }
            }
            if (anyInvalid) {
                problems.add("app.api.allowed-origins enthaelt einen ungueltigen Eintrag: jede Origin muss eine "
                        + "exakte https-Origin ohne '*', ohne Pfad, ohne Leerzeichen und ohne Loopback/http sein.");
            }
        }

        // 5) SMTP-Zugangsdaten nur pruefen, wenn Echtversand aktiviert ist (sonst nicht erforderlich).
        if (liveSendEnabled && (isBlank(mailHost) || isBlank(mailUsername) || isBlank(mailPassword))) {
            problems.add("app.mail.live-send-enabled ist aktiviert, aber SMTP-Zugangsdaten fehlen: "
                    + "spring.mail.host, spring.mail.username und spring.mail.password muessen gesetzt sein.");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Unsichere oder unvollstaendige Produktionskonfiguration (Profil 'prod'):"
                            + System.lineSeparator() + " - "
                            + String.join(System.lineSeparator() + " - ", problems));
        }
    }

    /** {@code true}, wenn der Wert {@code null}, leer oder nur aus Leerraum besteht. */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * {@code true} nur fuer eine gueltige, OEFFENTLICHE https-URL. Baut auf {@link TrackingLinkPolicy}
     * auf (verwirft CR/LF/Leerraeume, gefaehrliche Schemata, eingebettete Zugangsdaten, fehlenden Host) und
     * verschaerft zusaetzlich: das Schema MUSS {@code https} sein und der Host darf KEINE Loopback-Adresse
     * sein (in Produktion ist Loopback fuer externe Empfaenger nutzlos und daher verboten).
     */
    private static boolean isSecurePublicHttpsUrl(String url) {
        if (isBlank(url)) {
            return false;
        }
        if (!TrackingLinkPolicy.isAcceptableBaseUrl(url)) {
            return false;
        }
        URI uri = parse(url.trim());
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.toLowerCase(Locale.ROOT).equals("https")) {
            return false;
        }
        String host = uri.getHost();
        return host != null && !host.isBlank() && !isLoopbackHost(host);
    }

    /**
     * {@code true} nur fuer eine exakte https-Origin: {@code https://host[:port]} ohne Wildcard, ohne
     * Leerraum, ohne eingebettete Zugangsdaten, ohne Loopback/http und OHNE Pfad/Query/Fragment.
     */
    private static boolean isExactHttpsOrigin(String origin) {
        if (isBlank(origin) || containsWhitespace(origin) || "*".equals(origin.trim())) {
            return false;
        }
        URI uri = parse(origin.trim());
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.toLowerCase(Locale.ROOT).equals("https")) {
            return false;
        }
        // Keine eingebetteten Zugangsdaten (User-Info bzw. '@' in der Authority).
        String authority = uri.getAuthority();
        if (uri.getUserInfo() != null || (authority != null && authority.indexOf('@') >= 0)) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || isLoopbackHost(host)) {
            return false;
        }
        // Exakte Origin => kein Pfad, keine Query, kein Fragment.
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty()) {
            return false;
        }
        return uri.getRawQuery() == null && uri.getRawFragment() == null;
    }

    /** Parst den Wert als {@link URI}; bei Syntaxfehlern {@code null} (fail-closed). */
    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    /** {@code true}, wenn der String irgendein Leerraumzeichen enthaelt. */
    private static boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** {@code true} fuer {@code localhost}, {@code 127.0.0.1} oder IPv6-Loopback ({@code ::1}/{@code [::1]}). */
    private static boolean isLoopbackHost(String host) {
        String hostLower = host.toLowerCase(Locale.ROOT);
        return hostLower.equals("localhost")
                || hostLower.equals("127.0.0.1")
                || hostLower.equals("::1")
                || hostLower.equals("[::1]");
    }
}
