package de.internal.awareness.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft die Konfigurations-Bindung von {@code app.mail.*} (kommaseparierte Listen, Flag) auf
 * {@link AppMailProperties} und dass die gebundene Konfiguration die erwartete Allowlist-Semantik ergibt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "app.mail.live-send-enabled=true",
        "app.mail.allowed-senders=training@example.invalid,partner.invalid",
        "app.mail.allowed-recipient-domains=example.invalid"
})
class AppMailPropertiesBindingTest {

    @Autowired
    private AppMailProperties props;

    @Test
    void bindsFlagAndCommaSeparatedLists() {
        assertThat(props.isLiveSendEnabled()).isTrue();
        assertThat(props.getAllowedSenders()).containsExactly("training@example.invalid", "partner.invalid");
        assertThat(props.getAllowedRecipientDomains()).containsExactly("example.invalid");
    }

    @Test
    void boundAllowlistsEnforceExpectedSemantics() {
        assertThat(props.isSenderAllowed("training@example.invalid")).isTrue(); // exakte Adresse
        assertThat(props.isSenderAllowed("anyone@partner.invalid")).isTrue();   // Domain-Eintrag
        assertThat(props.isSenderAllowed("evil@notallowed.invalid")).isFalse();
        assertThat(props.isRecipientDomainAllowed("user@example.invalid")).isTrue();
        assertThat(props.isRecipientDomainAllowed("user@other.invalid")).isFalse();
    }
}
