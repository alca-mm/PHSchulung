package de.internal.awareness.contact;

import de.internal.awareness.support.SqliteFlywayJpaTest;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistenz von {@link Contact}: Speichern/Reload, case-insensitive Eindeutigkeit der E-Mail-Adresse
 * (DB-Unique-Index mit COLLATE NOCASE), Ablehnung ungueltiger Adressen und die case-insensitive
 * Existenzpruefung.
 */
@SqliteFlywayJpaTest
class ContactPersistenceTest {

    @Autowired
    private ContactRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savesAndReloadsContact() {
        Contact saved = repository.saveAndFlush(new Contact("alice@example.invalid", "Alice"));
        entityManager.clear();

        Optional<Contact> reloaded = repository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getEmail()).isEqualTo("alice@example.invalid");
        assertThat(reloaded.get().getDisplayName()).isEqualTo("Alice");
        assertThat(reloaded.get().getCreatedAt()).isNotNull();
    }

    @Test
    void existsByEmailIgnoreCaseIsCaseInsensitive() {
        repository.saveAndFlush(new Contact("bob@example.invalid", null));
        entityManager.clear();

        assertThat(repository.existsByEmailIgnoreCase("BOB@example.invalid")).isTrue();
        assertThat(repository.existsByEmailIgnoreCase("carol@example.invalid")).isFalse();
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() {
        repository.saveAndFlush(new Contact("dora@example.invalid", "Dora"));

        // COLLATE NOCASE: die abweichende Gross-/Kleinschreibung gilt als dieselbe Adresse -> DB-Ablehnung.
        assertThatThrownBy(() -> repository.saveAndFlush(new Contact("DORA@Example.invalid", "Andere")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("UNIQUE constraint failed");
    }

    @Test
    void blankEmailIsRejected() {
        assertThatThrownBy(() -> repository.saveAndFlush(new Contact("   ", "Leer")))
                .isInstanceOfAny(ConstraintViolationException.class, DataIntegrityViolationException.class);
    }

    @Test
    void invalidEmailIsRejected() {
        assertThatThrownBy(() -> repository.saveAndFlush(new Contact("not-an-email", "Ungueltig")))
                .isInstanceOfAny(ConstraintViolationException.class, DataIntegrityViolationException.class);
    }
}
