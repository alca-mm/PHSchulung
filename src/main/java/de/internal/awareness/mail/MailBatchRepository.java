package de.internal.awareness.mail;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring-Data-Repository fuer {@link MailBatch}.
 */
public interface MailBatchRepository extends JpaRepository<MailBatch, Long> {

    /** Alle Versandvorgaenge fuer die Historie, neueste zuerst (stabiler Tiebreaker ueber id). */
    List<MailBatch> findAllByOrderByCreatedAtDescIdDesc();
}
