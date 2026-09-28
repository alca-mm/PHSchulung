package de.internal.awareness.tracking;

import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.recipient.DeliveryStatus;
import de.internal.awareness.tracking.TrackingDeliveryRepository.DeliveryDetailView;
import de.internal.awareness.tracking.TrackingDeliveryRepository.TimelineEventView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Nur-Lese-Aufbereitungsdienst fuer die Detailseite einer einzelnen {@link MailDelivery} (Composer-Tracking):
 * er verknuepft die schlanke Detailprojektion mit der chronologischen Ereignis-Timeline zu einem einzigen,
 * anzeigefertigen {@link DeliveryDetail}.
 *
 * <p><b>Performance / N+1</b>: Der Dienst laedt die Daten mit genau ZWEI Abfragen (Detailprojektion +
 * Ereignis-Timeline, siehe {@link TrackingDeliveryRepository}) - unabhaengig von der Anzahl der Ereignisse. Es
 * wird keine Abfrage je Ereignis ausgefuehrt.</p>
 *
 * <p><b>Sicherheit/Datenschutz</b>: Weder Token noch Token-Hash, weder IP-Adressen, User-Agents noch
 * Fingerprints werden gelesen, in Records uebernommen oder geloggt. Ausgegeben werden nur die ohnehin bekannte
 * Empfaenger-Identitaet (Name/E-Mail), der Versandvorgang, der Anhangname sowie Zeitpunkte, Status, Versuchs-
 * zaehler und die Ereignisse (Typ + Zeitpunkt).</p>
 */
@Service
public class TrackingDeliveryService {

    private final TrackingDeliveryRepository repository;

    public TrackingDeliveryService(TrackingDeliveryRepository repository) {
        this.repository = repository;
    }

    /**
     * Ein einzelnes Ereignis der Timeline.
     *
     * @param type       Art des Ereignisses (z. B. {@link TrackingEventType#LINK_CLICK})
     * @param occurredAt Zeitpunkt des Ereignisses (UTC)
     */
    public record TimelineEntry(TrackingEventType type, Instant occurredAt) {
    }

    /**
     * Vollstaendige, anzeigefertige Detailsicht einer Zustellung inklusive Klick-Auswertung und Timeline.
     *
     * @param deliveryId         technische Id der Zustellung
     * @param recipientName      Anzeigename des Empfaengers (kann {@code null} sein)
     * @param email              E-Mail-Adresse des Empfaengers
     * @param batchId            Id des Versandvorgangs
     * @param batchSubject       Betreff des Versandvorgangs
     * @param attachmentFilename Anhang-Downloadname ({@code null}, wenn ohne Anhang)
     * @param sentAt             Sendezeitpunkt ({@code null}, solange nicht erfolgreich versendet)
     * @param status             Versandstatus der Zustellung
     * @param attemptCount       Anzahl der Versandversuche
     * @param triggered          ob der Trainingslink mindestens einmal ausgeloest wurde ({@code actionCount > 0})
     * @param actionCount        Gesamtzahl der Ereignisse ({@code timeline.size()})
     * @param firstAction        Zeitpunkt des ersten Ereignisses ({@code null}, falls keines)
     * @param lastAction         Zeitpunkt des letzten Ereignisses ({@code null}, falls keines)
     * @param timeline           chronologisch aufsteigende Liste aller Ereignisse (kann leer sein)
     */
    public record DeliveryDetail(Long deliveryId, String recipientName, String email, Long batchId,
                                 String batchSubject, String attachmentFilename, Instant sentAt,
                                 DeliveryStatus status, int attemptCount, boolean triggered, long actionCount,
                                 Instant firstAction, Instant lastAction, List<TimelineEntry> timeline) {
    }

    /**
     * Laedt die Detailsicht einer Zustellung. Existiert keine {@link MailDelivery} mit dieser Id (oder ist die Id
     * {@code null}), wird ein leeres {@link Optional} zurueckgegeben; die Weboberflaeche uebersetzt dies in eine
     * kontrollierte 404-Antwort.
     *
     * <p>{@code actionCount} entspricht der Anzahl der Ereignisse; {@code firstAction}/{@code lastAction} sind der
     * fruehste bzw. spaeteste Ereigniszeitpunkt (bzw. {@code null}, falls keine Ereignisse vorliegen); da die
     * Timeline bereits aufsteigend nach {@code occurredAt} geladen wird, sind dies das erste bzw. letzte Element.
     * {@code triggered} ist {@code true}, sobald mindestens ein Ereignis existiert.</p>
     *
     * @param id technische Id der Zustellung (darf {@code null} sein)
     * @return die Detailsicht oder ein leeres {@link Optional}, falls keine passende Zustellung existiert
     */
    @Transactional(readOnly = true)
    public Optional<DeliveryDetail> findDelivery(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        Optional<DeliveryDetailView> detailView = repository.findDetailById(id);
        if (detailView.isEmpty()) {
            return Optional.empty();
        }
        DeliveryDetailView d = detailView.get();

        List<TimelineEventView> events = repository.findTimeline(id);
        List<TimelineEntry> timeline = new ArrayList<>(events.size());
        for (TimelineEventView e : events) {
            timeline.add(new TimelineEntry(e.getType(), e.getOccurredAt()));
        }

        long actionCount = timeline.size();
        boolean triggered = actionCount > 0L;
        // Die Timeline ist aufsteigend geordnet: erstes Element = fruehestes, letztes = spaetestes Ereignis.
        Instant firstAction = timeline.isEmpty() ? null : timeline.get(0).occurredAt();
        Instant lastAction = timeline.isEmpty() ? null : timeline.get(timeline.size() - 1).occurredAt();

        return Optional.of(new DeliveryDetail(
                d.getDeliveryId(), d.getRecipientName(), d.getEmail(), d.getBatchId(),
                d.getBatchSubject(), d.getAttachmentFilename(), d.getSentAt(), d.getStatus(),
                d.getAttemptCount(), triggered, actionCount, firstAction, lastAction,
                List.copyOf(timeline)));
    }
}
