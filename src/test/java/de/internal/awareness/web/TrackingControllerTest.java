package de.internal.awareness.web;

import de.internal.awareness.contact.Contact;
import de.internal.awareness.contact.ContactRepository;
import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailBatchRepository;
import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.mail.MailDeliveryRepository;
import de.internal.awareness.tracking.MailTrackingEventRepository;
import de.internal.awareness.tracking.TrackingTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * MVC-Tests des oeffentlichen Trainingslink-Endpoints {@code GET /t/{token}}: gueltiger Token zeigt die
 * Trainingsseite und erzeugt (mehrfach) LINK_CLICK-Ereignisse; unbekannte/ungueltige/zu lange Tokens werden
 * neutral (404) beantwortet, ohne interne Daten preiszugeben (keine E-Mail, keine IDs, kein Token/Hash).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TrackingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MailBatchRepository batchRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private MailDeliveryRepository deliveryRepository;

    @Autowired
    private MailTrackingEventRepository eventRepository;

    private record Prepared(MailDelivery delivery, String token, String hash, String email) {
    }

    private Prepared prepareDelivery(String email) {
        MailBatch batch = batchRepository.saveAndFlush(new MailBatch(
                "Betreff", "Hallo", "training@example.invalid", null, null, null, 1));
        Contact contact = contactRepository.saveAndFlush(new Contact(email, "Name"));
        TrackingTokens.GeneratedToken token = TrackingTokens.generate();
        MailDelivery delivery = deliveryRepository.saveAndFlush(
                new MailDelivery(batch, contact, token.tokenHash()));
        return new Prepared(delivery, token.token(), token.tokenHash(), email);
    }

    // 7 + no-cache
    @Test
    void validTokenShowsTrainingPage() throws Exception {
        Prepared p = prepareDelivery("a@example.invalid");
        mockMvc.perform(get("/t/{token}", p.token()))
                .andExpect(status().isOk())
                .andExpect(view().name("tracking/training"))
                .andExpect(content().string(containsString("Security Awareness Training")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    // 8
    @Test
    void validTokenCreatesOneClickEvent() throws Exception {
        Prepared p = prepareDelivery("a@example.invalid");
        mockMvc.perform(get("/t/{token}", p.token())).andExpect(status().isOk());
        assertThat(eventRepository.countByDelivery(p.delivery())).isEqualTo(1L);
    }

    // 9
    @Test
    void secondCallCreatesSecondEvent() throws Exception {
        Prepared p = prepareDelivery("a@example.invalid");
        mockMvc.perform(get("/t/{token}", p.token())).andExpect(status().isOk());
        mockMvc.perform(get("/t/{token}", p.token())).andExpect(status().isOk());
        assertThat(eventRepository.countByDelivery(p.delivery())).isEqualTo(2L);
    }

    // 10
    @Test
    void unknownTokenCreatesNoEvent() throws Exception {
        Prepared p = prepareDelivery("a@example.invalid");
        String unknown = TrackingTokens.generate().token(); // gueltiges Format, aber nicht vergeben
        mockMvc.perform(get("/t/{token}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(view().name("tracking/invalid"));
        assertThat(eventRepository.countByDelivery(p.delivery())).isZero();
    }

    // 11
    @Test
    void overlongTokenIsRejectedControlled() throws Exception {
        String tooLong = "a".repeat(300);
        mockMvc.perform(get("/t/{token}", tooLong))
                .andExpect(status().isNotFound())
                .andExpect(view().name("tracking/invalid"));
    }

    // 16 + 17: der oeffentliche Endpoint liegt NICHT hinter dem Login und zeigt keine Admin-Navigation.
    @Test
    void publicEndpointIsNotBehindLoginAndHasNoAdminNav() throws Exception {
        Prepared p = prepareDelivery("a@example.invalid");
        mockMvc.perform(get("/t/{token}", p.token()))
                .andExpect(status().isOk())
                .andExpect(view().name("tracking/training"))
                // Kein Redirect zur Loginseite.
                .andExpect(header().doesNotExist("Location"))
                // Keine Admin-Navigation / kein Logout auf der Trainingsseite.
                .andExpect(content().string(not(containsString("Kampagnen"))))
                .andExpect(content().string(not(containsString("Logout"))));
    }

    // 12 + 13 + 14
    @Test
    void responsesDoNotLeakInternalData() throws Exception {
        Prepared p = prepareDelivery("secret.person@example.invalid");

        // Trainingsseite (gueltiger Token): keine Adresse, kein Token, kein Hash.
        mockMvc.perform(get("/t/{token}", p.token()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(p.email()))))
                .andExpect(content().string(not(containsString(p.token()))))
                .andExpect(content().string(not(containsString(p.hash()))));

        // Neutrale 404-Seite (unbekannter Token): ebenfalls keine internen Daten.
        String unknown = TrackingTokens.generate().token();
        mockMvc.perform(get("/t/{token}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString(p.email()))))
                .andExpect(content().string(not(containsString("delivery"))));
    }
}
