package de.internal.awareness.web;

import de.internal.awareness.contact.Contact;
import de.internal.awareness.contact.ContactService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * MVC-Tests der globalen Empfaengerliste (Kontakte). Voller Stack ueber MockMvc gegen die isolierte
 * SQLite-DB. Prueft Uebersicht, einzelnes Hinzufuegen inkl. Duplikat- und Validierungsfall sowie den
 * Massenimport (Erfolgsmeldung und Meldung ungueltiger Zeilen).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.test.context.TestExecutionListeners(listeners = org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener.class, mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@WithMockUser(username = "test-admin", roles = "ADMIN")
class ContactControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContactService contactService;

    @Test
    void recipientOverviewWorks() throws Exception {
        mockMvc.perform(get("/recipients"))
                .andExpect(status().isOk())
                .andExpect(view().name("contacts/list"))
                .andExpect(content().string(containsString("Empfänger")));
    }

    @Test
    void addSingleContactRedirectsWithSuccess() throws Exception {
        mockMvc.perform(post("/recipients").with(csrf())
                        .param("email", "alice@example.invalid")
                        .param("displayName", "Alice"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/recipients"))
                .andExpect(flash().attributeExists("flashSuccess"));

        assertThat(contactService.findAll())
                .extracting(Contact::getEmail)
                .contains("alice@example.invalid");
    }

    @Test
    void duplicateContactIsRejectedWithFlashError() throws Exception {
        contactService.addContact("dup@example.invalid", null);

        mockMvc.perform(post("/recipients").with(csrf())
                        .param("email", "dup@example.invalid"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/recipients"))
                .andExpect(flash().attributeExists("flashError"));

        assertThat(contactService.findAll())
                .filteredOn(c -> "dup@example.invalid".equalsIgnoreCase(c.getEmail()))
                .hasSize(1);
    }

    @Test
    void bulkImportRedirectsWithSuccess() throws Exception {
        mockMvc.perform(post("/recipients/bulk").with(csrf())
                        .param("text", "a@example.invalid\nMax Mustermann <b@example.invalid>"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/recipients"))
                .andExpect(flash().attribute("flashSuccess", containsString("2 Empfänger hinzugefügt")));

        assertThat(contactService.count()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void bulkImportReportsInvalidLines() throws Exception {
        mockMvc.perform(post("/recipients/bulk").with(csrf())
                        .param("text", "ok@example.invalid\nkein-email"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/recipients"))
                .andExpect(flash().attributeExists("flashInvalidLines"));
    }

    @Test
    void blankEmailShowsValidationError() throws Exception {
        mockMvc.perform(post("/recipients").with(csrf())
                        .param("email", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("contacts/list"));
    }

    @Test
    void overviewShowsAddedContactEmail() throws Exception {
        contactService.addContact("visible@example.invalid", "Sichtbar");

        mockMvc.perform(get("/recipients"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("visible@example.invalid")));
    }
}
