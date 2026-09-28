package de.internal.awareness.tracking;

import de.internal.awareness.mail.MailBatch;
import de.internal.awareness.mail.MailDelivery;
import de.internal.awareness.mail.MailDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Registriert Trainingslink-Klicks und wertet sie je Zustellung/Versandvorgang aus.
 *
 * <p>Sicherheit/Datenschutz: Der Lookup erfolgt ausschliesslich ueber den SHA-256-Hash des eingehenden
 * Tokens; der Klartext-Token wird NIE gespeichert und NIE geloggt. Es werden bewusst KEINE IP-Adressen,
 * User-Agents, Fingerprints oder Geodaten erfasst - nur Zuordnung zur Zustellung, Ereignistyp und Zeitpunkt.
 * Ein Klick entsteht ausschliesslich durch die bewusste Benutzeraktion auf den sichtbaren Link; das blosse
 * Oeffnen einer Datei erzeugt KEIN Ereignis (kein Open-/Pixel-/Remote-Tracking).</p>
 */
@Service
public class MailTrackingService {

    private static final Logger log = LoggerFactory.getLogger(MailTrackingService.class);

    /** Zulaessige Tokenlaenge (Base64URL von 256 Bit = 43 Zeichen; grosszuegige Obergrenze gegen Missbrauch). */
    static final int MIN_TOKEN_LENGTH = 16;
    static final int MAX_TOKEN_LENGTH = 128;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    private final MailDeliveryRepository deliveryRepository;
    private final MailTrackingEventRepository eventRepository;

    public MailTrackingService(MailDeliveryRepository deliveryRepository,
                               MailTrackingEventRepository eventRepository) {
        this.deliveryRepository = deliveryRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * Registriert einen Klick fuer den (Klartext-)Token: Token pruefen, hashen, Zustellung finden und ein
     * {@code LINK_CLICK}-Ereignis speichern. Mehrfache Aufrufe erzeugen mehrere Ereignisse.
     *
     * @return {@code true}, wenn der Token gueltig war und ein Ereignis gespeichert wurde; sonst {@code false}
     *         (unbekannter/ungueltiger Token - es werden keinerlei interne Informationen preisgegeben)
     */
    @Transactional
    public boolean registerClick(String rawToken) {
        if (!isPlausibleToken(rawToken)) {
            // Kontrolliert ablehnen, keine schwere Verarbeitung, keine Details.
            return false;
        }
        String hash = TrackingTokens.hash(rawToken);
        Optional<MailDelivery> delivery = deliveryRepository.findByTrackingTokenHash(hash);
        if (delivery.isEmpty()) {
            return false;
        }
        eventRepository.saveAndFlush(new MailTrackingEvent(delivery.get(), TrackingEventType.LINK_CLICK, Instant.now()));
        // Datensparsam: nur die Delivery-Id, nie Token/Hash/Adresse.
        log.info("Trainingslink-Klick registriert fuer deliveryId={}", delivery.get().getId());
        return true;
    }

    /** Grobe Syntax-/Groessenpruefung des Tokens (fail-closed), bevor gehasht/abgefragt wird. */
    static boolean isPlausibleToken(String token) {
        return token != null
                && token.length() >= MIN_TOKEN_LENGTH
                && token.length() <= MAX_TOKEN_LENGTH
                && TOKEN_PATTERN.matcher(token).matches();
    }

    /**
     * Tracking-Auswertung je Zustellung eines Versandvorgangs (fuer die Batch-Detailseite).
     *
     * @param delivery   die Zustellung
     * @param clickCount Anzahl der Klicks
     * @param firstClick Zeitpunkt des ersten Klicks ({@code null}, falls keiner)
     * @param lastClick  Zeitpunkt des letzten Klicks ({@code null}, falls keiner)
     */
    public record DeliveryTracking(MailDelivery delivery, long clickCount, Instant firstClick, Instant lastClick) {

        /** Ob der Trainingslink mindestens einmal ausgeloest wurde. */
        public boolean clicked() {
            return clickCount > 0;
        }
    }

    /** Zusammenfassung eines Versandvorgangs inkl. Tracking. */
    public record BatchTracking(long total, long sent, long failed, long notSent,
                                long respondingRecipients, long totalClicks) {
    }

    /** Auswertung aller Zustellungen eines Versandvorgangs (Klickzahl, erster/letzter Klick je Empfaenger). */
    @Transactional(readOnly = true)
    public List<DeliveryTracking> deliveryTracking(MailBatch batch) {
        List<DeliveryTracking> rows = new ArrayList<>();
        for (MailDelivery delivery : deliveryRepository.findByBatch(batch)) {
            long clicks = eventRepository.countByDelivery(delivery);
            Instant first = eventRepository.findFirstByDeliveryOrderByOccurredAtAsc(delivery)
                    .map(MailTrackingEvent::getOccurredAt).orElse(null);
            Instant last = eventRepository.findFirstByDeliveryOrderByOccurredAtDesc(delivery)
                    .map(MailTrackingEvent::getOccurredAt).orElse(null);
            rows.add(new DeliveryTracking(delivery, clicks, first, last));
        }
        return rows;
    }

    /** Aggregierte Batch-Zusammenfassung inkl. Klick-Statistik. */
    @Transactional(readOnly = true)
    public BatchTracking batchTracking(MailBatch batch) {
        long total = 0;
        long sent = 0;
        long failed = 0;
        long notSent = 0;
        for (MailDelivery delivery : deliveryRepository.findByBatch(batch)) {
            total++;
            switch (delivery.getStatus()) {
                case SENT -> sent++;
                case FAILED -> failed++;
                case NOT_SENT -> notSent++;
                default -> { /* alle Faelle abgedeckt */ }
            }
        }
        long responding = eventRepository.countRespondingDeliveriesForBatch(batch);
        long totalClicks = eventRepository.countEventsForBatch(batch);
        return new BatchTracking(total, sent, failed, notSent, responding, totalClicks);
    }
}
