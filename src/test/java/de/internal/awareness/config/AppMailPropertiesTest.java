package de.internal.awareness.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direkte Unit-Tests der sicherheitskritischen Allowlist-Semantik von {@link AppMailProperties}
 * (ohne Spring-Kontext). Deckt insbesondere die <b>fail-closed</b>-Pfade ab, die sonst nur indirekt
 * ueber die Versandtests beruehrt wuerden.
 */
class AppMailPropertiesTest {

    @Test
    void liveSendIsDisabledByDefault() {
        assertThat(new AppMailProperties().isLiveSendEnabled()).isFalse();
    }

    @Test
    void senderAllowlistIsFailClosedWhenEmpty() {
        AppMailProperties props = new AppMailProperties();
        assertThat(props.getAllowedSenders()).isEmpty();
        assertThat(props.isSenderAllowed("training@example.invalid")).isFalse();
    }

    @Test
    void senderExactAddressMatchesCaseInsensitively() {
        AppMailProperties props = new AppMailProperties();
        props.setAllowedSenders(List.of("Training@Example.Invalid"));

        assertThat(props.isSenderAllowed("training@example.invalid")).isTrue();
        assertThat(props.isSenderAllowed("TRAINING@EXAMPLE.INVALID")).isTrue();
        assertThat(props.isSenderAllowed("other@example.invalid")).isFalse();
    }

    @Test
    void senderDomainEntryMatchesAnyAddressInThatDomain() {
        AppMailProperties props = new AppMailProperties();
        props.setAllowedSenders(List.of("example.invalid"));

        assertThat(props.isSenderAllowed("anyone@example.invalid")).isTrue();
        assertThat(props.isSenderAllowed("someone.else@example.invalid")).isTrue();
        assertThat(props.isSenderAllowed("anyone@other.invalid")).isFalse();
    }

    @Test
    void senderNullOrBlankIsNeverAllowed() {
        AppMailProperties props = new AppMailProperties();
        props.setAllowedSenders(List.of("example.invalid"));

        assertThat(props.isSenderAllowed(null)).isFalse();
        assertThat(props.isSenderAllowed("   ")).isFalse();
        assertThat(props.isSenderAllowed("no-at-sign")).isFalse();
    }

    @Test
    void recipientDomainAllowlistEmptyAllowsEveryone() {
        AppMailProperties props = new AppMailProperties();
        assertThat(props.getAllowedRecipientDomains()).isEmpty();
        assertThat(props.isRecipientDomainAllowed("x@anywhere.invalid")).isTrue();
    }

    @Test
    void recipientDomainAllowlistWhenSetAllowsOnlyListedDomains() {
        AppMailProperties props = new AppMailProperties();
        props.setAllowedRecipientDomains(List.of("Example.Invalid"));

        assertThat(props.isRecipientDomainAllowed("x@example.invalid")).isTrue();
        assertThat(props.isRecipientDomainAllowed("x@other.invalid")).isFalse();
        assertThat(props.isRecipientDomainAllowed("malformed-without-domain")).isFalse();
        assertThat(props.isRecipientDomainAllowed(null)).isFalse();
    }

    @Test
    void blankEntriesAreIgnoredDuringNormalization() {
        AppMailProperties props = new AppMailProperties();
        props.setAllowedSenders(List.of("  training@example.invalid  ", "   ", ""));

        assertThat(props.getAllowedSenders()).containsExactly("training@example.invalid");
        assertThat(props.isSenderAllowed("training@example.invalid")).isTrue();
    }
}
