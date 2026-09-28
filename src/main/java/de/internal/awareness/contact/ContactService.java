package de.internal.awareness.contact;

import de.internal.awareness.recipient.BulkImportResult;
import de.internal.awareness.recipient.BulkRecipientParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Kleiner Anwendungsdienst fuer die globale, kampagnenunabhaengige Kontaktliste: Kontakte laden, einen
 * einzelnen Kontakt anlegen, mehrere Kontakte per Freitext importieren und eine vom Client uebergebene
 * Auswahl serverseitig auf gueltige Kontakt-IDs aufloesen.
 *
 * <p>Analog zu {@code de.internal.awareness.recipient.RecipientService}, jedoch ohne Kampagnenbezug und
 * ohne Tracking-Identitaet: Die Eindeutigkeit der Adresse gilt hier global (case-insensitive).</p>
 */
@Service
@Transactional(readOnly = true)
public class ContactService {

    private final ContactRepository repository;
    private final BulkRecipientParser bulkParser;

    public ContactService(ContactRepository repository, BulkRecipientParser bulkParser) {
        this.repository = repository;
        this.bulkParser = bulkParser;
    }

    /** Alle Kontakte fuer die Liste, neueste zuerst (stabiler Tiebreaker ueber id). */
    public List<Contact> findAll() {
        return repository.findAllByOrderByCreatedAtDescIdDesc();
    }

    /** Gesamtzahl der gespeicherten Kontakte. */
    public long count() {
        return repository.count();
    }

    /**
     * Legt einen neuen Kontakt in der globalen Liste an.
     *
     * <p>Ablauf: Duplikat-Vorpruefung (dieselbe Adresse ist global nur einmal erlaubt) -&gt; Kontakt bauen
     * -&gt; speichern. Ist die Adresse bereits vorhanden, wird eine {@link DuplicateContactException}
     * geworfen. Ungueltige Eingaben (leere/ungueltige E-Mail) werden bewusst NICHT hier abgefangen,
     * sondern fallen wie bisher durch Bean Validation beim Flush (siehe {@code RecipientService.addRecipient}).</p>
     */
    @Transactional
    public Contact addContact(String email, String displayName) {
        if (repository.existsByEmailIgnoreCase(email)) {
            throw new DuplicateContactException(email);
        }
        Contact contact = new Contact(
                email == null ? null : email.trim(),
                (displayName == null || displayName.isBlank()) ? null : displayName.trim());
        return repository.saveAndFlush(contact);
    }

    /**
     * Importiert mehrere Kontakte aus Freitext (eine Zeile je Kontakt, siehe {@link BulkRecipientParser}).
     * Ungueltige Zeilen werden gemeldet, nicht angelegt. Duplikate werden case-insensitive uebersprungen -
     * sowohl gegen bereits vorhandene Kontakte als auch innerhalb desselben Imports (die erste Nennung
     * gewinnt).
     *
     * <p>Da die Adressen zuvor validiert und dedupliziert wurden, ist beim Speichern der verbleibenden,
     * eindeutigen Kontakte kein Flush-Fehler und keine Verletzung des Unique-Index zu erwarten.</p>
     */
    @Transactional
    public BulkImportResult importContacts(String rawText) {
        BulkRecipientParser.ParsedBulk parsed = bulkParser.parse(rawText);

        int added = 0;
        int duplicates = 0;
        Set<String> seenEmails = new HashSet<>();

        for (BulkRecipientParser.ParsedRecipient candidate : parsed.valid()) {
            String normalized = candidate.email().toLowerCase(Locale.ROOT);
            boolean newInBatch = seenEmails.add(normalized);
            if (!newInBatch || repository.existsByEmailIgnoreCase(candidate.email())) {
                duplicates++;
                continue;
            }
            repository.saveAndFlush(new Contact(candidate.email(), candidate.displayName()));
            added++;
        }
        return new BulkImportResult(added, duplicates, parsed.invalidLines());
    }

    /**
     * Loest eine vom Client uebergebene Auswahl von Kontakt-IDs zu Entities auf (serverseitige Pruefung
     * der Auswahl des E-Mail-Composers).
     *
     * <p>Der Server darf einer clientseitig uebermittelten Auswahl niemals vertrauen: Unbekannte oder
     * manipulierte IDs werden hier abgelehnt ({@link ContactNotFoundException}). Die Reihenfolge der
     * uebergebenen IDs bleibt erhalten; doppelte IDs werden uebersprungen (die erste Nennung gewinnt).
     * Ist {@code ids} {@code null} oder leer, wird eine leere Liste zurueckgegeben.</p>
     */
    public List<Contact> resolveSelected(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        List<Contact> resolved = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        for (Long id : ids) {
            if (!seenIds.add(id)) {
                continue;
            }
            Contact contact = repository.findById(id)
                    .orElseThrow(() -> new ContactNotFoundException(id));
            resolved.add(contact);
        }
        return resolved;
    }
}
