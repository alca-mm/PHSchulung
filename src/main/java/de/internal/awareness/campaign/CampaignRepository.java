package de.internal.awareness.campaign;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring-Data-Repository fuer {@link Campaign}. Standard-CRUD (inkl. findById) genuegt
 * fuer die aktuellen Anwendungsfaelle; keine spekulativen Queries.
 */
public interface CampaignRepository extends JpaRepository<Campaign, Long> {
}
