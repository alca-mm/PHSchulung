package de.internal.awareness.file;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring-Data-Repository fuer {@link GeneratedFile}.
 */
public interface GeneratedFileRepository extends JpaRepository<GeneratedFile, Long> {

    /** Alle Dateien fuer die Bibliotheksuebersicht, neueste zuerst (stabiler Tiebreaker ueber id). */
    List<GeneratedFile> findAllByOrderByCreatedAtDescIdDesc();
}
