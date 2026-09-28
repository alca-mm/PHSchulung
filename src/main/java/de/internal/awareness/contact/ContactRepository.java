package de.internal.awareness.contact;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring-Data-Repository fuer {@link Contact}.
 */
public interface ContactRepository extends JpaRepository<Contact, Long> {

    /** Alle Kontakte fuer die Liste, neueste zuerst (stabiler Tiebreaker ueber id). */
    List<Contact> findAllByOrderByCreatedAtDescIdDesc();

    /**
     * Prueft (case-insensitive), ob eine Adresse bereits existiert. Grundlage der verstaendlichen
     * Duplikat-Meldung; der Unique-Index (email COLLATE NOCASE) bleibt der DB-seitige Schutz.
     */
    boolean existsByEmailIgnoreCase(String email);
}
