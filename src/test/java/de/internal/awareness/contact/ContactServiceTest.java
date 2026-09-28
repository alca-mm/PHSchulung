package de.internal.awareness.contact;

import de.internal.awareness.recipient.BulkImportResult;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-Tests fuer {@link ContactService} gegen die reale (isolierte) SQLite-Test-DB. Deckt das Anlegen
 * einzelner Kontakte, die globale Duplikat-Behandlung (case-insensitive), den Massenimport aus Freitext,
 * die Ablehnung ungueltiger/leerer Adressen sowie das serverseitige Aufloesen einer Client-Auswahl ab.
 *
 * <p>Hinweis (wie im bestehenden {@code RecipientServiceTest}): pro Testmethode hoechstens EIN
 * fehlschlagender Flush. Zwei fehlschlagende {@code saveAndFlush} in derselben (Test-)Transaktion wuerden
 * beim zweiten Flush die bereits id-lose Entity des ersten Fehlversuchs erneut verarbeiten -&gt;
 * org.hibernate.AssertionFailure ("null identifier"). Die Duplikat-Vorpruefung wirft VOR jedem Flush, der
 * Massenimport speichert niemals ungueltige oder doppelte Zeilen - daher sind diese Faelle unbedenklich.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ContactServiceTest {

    @Autowired
    private ContactService contactService;

    @Autowired
    private ContactRepository contactRepository;

    // Contact kann angelegt werden, bleibt persistent, und die Liste zeigt gespeicherte Kontakte.
    @Test
    void addContactPersistsAndListShowsIt() {
        Contact saved = contactService.addContact("alice@example.invalid", "Alice");

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getEmail()).isEqualTo("alice@example.invalid");
        assertThat(saved.getDisplayName()).isEqualTo("Alice");
        assertThat(saved.getCreatedAt()).isNotNull();

        // bleibt persistent
        Contact reloaded = contactRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("alice@example.invalid");

        // Liste zeigt gespeicherte Kontakte
        assertThat(contactService.findAll())
                .extracting(Contact::getEmail)
                .containsExactly("alice@example.invalid");
        assertThat(contactService.count()).isEqualTo(1);
    }

    // Duplikat (abweichende Gross-/Kleinschreibung) wirft DuplicateContactException; nur eine Zeile bleibt.
    // Vorpruefung wirft VOR dem Flush - kein fehlschlagender Flush in dieser Transaktion.
    @Test
    void addContactRejectsDuplicateEmailIgnoringCase() {
        contactService.addContact("bob@example.invalid", null);

        assertThatExceptionOfType(DuplicateContactException.class)
                .isThrownBy(() -> contactService.addContact("BOB@Example.invalid", "Bob"))
                .satisfies(ex -> assertThat(ex.getEmail()).isEqualTo("BOB@Example.invalid"));

        assertThat(contactRepository.count()).isEqualTo(1);
    }

    // Massenimport: Mischung aus blanker Adresse und "Name <email>", Duplikate innerhalb des Imports und
    // ueber zwei Aufrufe werden gezaehlt und nicht doppelt gespeichert, ungueltige Zeilen gemeldet, leere
    // Zeilen ignoriert.
    @Test
    void importContactsAddsDeduplicatesAndReportsInvalid() {
        BulkImportResult first = contactService.importContacts(
                "\n a@example.invalid \n\nMax Mustermann <b@example.invalid>\nA@example.invalid\nkein-email\n   \n");

        assertThat(first.added()).isEqualTo(2);
        assertThat(first.duplicates()).isEqualTo(1);
        assertThat(first.invalidLines()).containsExactly("kein-email");

        assertThat(contactService.findAll())
                .extracting(Contact::getEmail)
                .containsExactlyInAnyOrder("a@example.invalid", "b@example.invalid");

        // Duplikat ueber zwei Aufrufe hinweg wird nicht erneut angelegt.
        BulkImportResult second = contactService.importContacts(
                "a@example.invalid\nc@example.invalid");

        assertThat(second.added()).isEqualTo(1);
        assertThat(second.duplicates()).isEqualTo(1);
        assertThat(second.invalidLines()).isEmpty();
        assertThat(contactRepository.count()).isEqualTo(3);
    }

    // Ungueltige Adresse: der EINZIGE fehlschlagende Flush dieser Testmethode.
    @Test
    void addContactRejectsInvalidEmailFormat() {
        assertThatThrownBy(() -> contactService.addContact("not-an-email", null))
                .isInstanceOfAny(ConstraintViolationException.class, DataIntegrityViolationException.class);
    }

    // Leere Adresse: der EINZIGE fehlschlagende Flush dieser Testmethode.
    @Test
    void addContactRejectsBlankEmail() {
        assertThatThrownBy(() -> contactService.addContact("   ", null))
                .isInstanceOfAny(ConstraintViolationException.class, DataIntegrityViolationException.class);
    }

    // resolveSelected loest eine gueltige Auswahl auf, lehnt unbekannte IDs ab und liefert fuer eine leere
    // Auswahl eine leere Liste.
    @Test
    void resolveSelectedResolvesValidRejectsUnknownAndHandlesEmpty() {
        Contact c1 = contactService.addContact("sel1@example.invalid", null);
        Contact c2 = contactService.addContact("sel2@example.invalid", null);

        assertThat(contactService.resolveSelected(List.of(c1.getId(), c2.getId())))
                .extracting(Contact::getId)
                .containsExactly(c1.getId(), c2.getId());

        assertThatExceptionOfType(ContactNotFoundException.class)
                .isThrownBy(() -> contactService.resolveSelected(List.of(999_999L)))
                .satisfies(ex -> assertThat(ex.getContactId()).isEqualTo(999_999L));

        assertThat(contactService.resolveSelected(List.of())).isEmpty();
    }
}
