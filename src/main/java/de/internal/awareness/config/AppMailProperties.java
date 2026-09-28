package de.internal.awareness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Anwendungsspezifische Schutz-Konfiguration fuer den Mailversand (Prefix {@code app.mail}).
 *
 * <p>Diese Klasse enthaelt bewusst KEINE SMTP-Zugangsdaten (die liegen unter {@code spring.mail.*} und
 * kommen ausschliesslich aus der Umgebung). Sie steuert die drei Sicherheitsschalter des Versands:</p>
 * <ul>
 *   <li>{@code live-send-enabled} - globaler Schalter; Default {@code false} verhindert versehentlichen
 *       echten Versand direkt nach dem Start.</li>
 *   <li>{@code allowed-senders} - Absender-Allowlist. <b>Fail-closed</b>: eine leere Liste erlaubt keinen
 *       Absender. Ein Eintrag mit {@code '@'} ist eine exakte Adresse (Gross-/Kleinschreibung egal), ein
 *       Eintrag ohne {@code '@'} eine erlaubte Absender-Domain.</li>
 *   <li>{@code allowed-recipient-domains} - Empfaenger-Domain-Allowlist mit "wenn gesetzt"-Semantik: eine
 *       leere Liste bedeutet keine Beschraenkung; ist sie gesetzt, sind nur diese Domains zulaessig.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.mail")
public class AppMailProperties {

    /** Globaler Schalter fuer echten SMTP-Versand. Default bewusst {@code false}. */
    private boolean liveSendEnabled = false;

    /** Erlaubte Absender (Adressen und/oder Domains). Fail-closed: leer => nichts erlaubt. */
    private List<String> allowedSenders = new ArrayList<>();

    /** Erlaubte Empfaenger-Domains. Leer => keine Beschraenkung ("wenn gesetzt"-Semantik). */
    private List<String> allowedRecipientDomains = new ArrayList<>();

    /**
     * Sichtbare Standard-Absenderadresse fuer den globalen E-Mail-Composer (KEIN Secret). Der Benutzer
     * muss den Absender nicht je Mail eintippen; er kommt aus dieser Konfiguration. Leer => kein Absender
     * konfiguriert (der Composer warnt dann und deaktiviert den Versand). Muss zusaetzlich durch
     * {@link #isSenderAllowed(String)} erlaubt sein. SMTP-Zugangsdaten werden hier NIE gespeichert.
     */
    private String defaultSender = null;

    /** Optionaler Anzeigename zum Standard-Absender (z. B. "IT Security"). */
    private String defaultSenderName = null;

    public boolean isLiveSendEnabled() {
        return liveSendEnabled;
    }

    public void setLiveSendEnabled(boolean liveSendEnabled) {
        this.liveSendEnabled = liveSendEnabled;
    }

    public List<String> getAllowedSenders() {
        return allowedSenders;
    }

    public void setAllowedSenders(List<String> allowedSenders) {
        this.allowedSenders = normalize(allowedSenders);
    }

    public List<String> getAllowedRecipientDomains() {
        return allowedRecipientDomains;
    }

    public void setAllowedRecipientDomains(List<String> allowedRecipientDomains) {
        this.allowedRecipientDomains = normalize(allowedRecipientDomains);
    }

    /** Die konfigurierte Standard-Absenderadresse (normalisiert, klein geschrieben) oder {@code null}, wenn keine gesetzt ist. */
    public String getDefaultSender() {
        return defaultSender;
    }

    public void setDefaultSender(String defaultSender) {
        this.defaultSender = lower(defaultSender);
    }

    /** Optionaler Anzeigename des Standard-Absenders (getrimmt) oder {@code null}, wenn keiner gesetzt ist. */
    public String getDefaultSenderName() {
        return defaultSenderName;
    }

    public void setDefaultSenderName(String defaultSenderName) {
        this.defaultSenderName = (defaultSenderName == null || defaultSenderName.isBlank())
                ? null : defaultSenderName.trim();
    }

    /**
     * {@code true}, wenn ein Standard-Absender konfiguriert UND durch die Absender-Allowlist erlaubt ist.
     * Nur dann darf der globale Composer versenden.
     */
    public boolean hasUsableDefaultSender() {
        return defaultSender != null && !defaultSender.isBlank() && isSenderAllowed(defaultSender);
    }

    /**
     * Prueft, ob eine Absenderadresse versenden darf. <b>Fail-closed</b>: ist die Allowlist leer oder die
     * Adresse leer, ist der Versand nicht erlaubt. Eintraege mit {@code '@'} matchen exakt (case-insensitive),
     * Eintraege ohne {@code '@'} matchen die Domain der Adresse.
     */
    public boolean isSenderAllowed(String email) {
        String normalized = lower(email);
        if (normalized == null || allowedSenders.isEmpty()) {
            return false;
        }
        String domain = domainOf(normalized);
        for (String entry : allowedSenders) {
            if (entry.contains("@")) {
                if (entry.equals(normalized)) {
                    return true;
                }
            } else if (domain != null && entry.equals(domain)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prueft, ob eine Empfaengeradresse aufgrund ihrer Domain zulaessig ist. "Wenn gesetzt"-Semantik: ist die
     * Liste leer, gibt es keine Beschraenkung ({@code true}); ist sie gesetzt, muss die Domain enthalten sein.
     */
    public boolean isRecipientDomainAllowed(String email) {
        if (allowedRecipientDomains.isEmpty()) {
            return true;
        }
        String domain = domainOf(lower(email));
        return domain != null && allowedRecipientDomains.contains(domain);
    }

    private static List<String> normalize(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    result.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return result;
    }

    private static String lower(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String domainOf(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return null;
        }
        return email.substring(at + 1);
    }
}
