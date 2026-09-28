package de.internal.awareness.tracking;

/**
 * Fachlicher Fehler: Es wurde eine Zustellung ueber eine Id angefragt, die nicht existiert.
 *
 * <p>Wird vom {@code TrackingDeliveryController} geworfen und vom globalen {@code WebExceptionHandler} in eine
 * kontrollierte 404-Antwort (gestylte, neutrale Fehlerseite) uebersetzt - analog zu
 * {@code CampaignNotFoundException}/{@code GeneratedFileNotFoundException}/{@code MailBatchNotFoundException}.
 * Es werden dabei keine internen Details (Stacktraces, Pfade) preisgegeben.</p>
 */
public class TrackingDeliveryNotFoundException extends RuntimeException {

    private final Long deliveryId;

    public TrackingDeliveryNotFoundException(Long deliveryId) {
        super("Zustellung nicht gefunden: " + deliveryId);
        this.deliveryId = deliveryId;
    }

    /** Die angefragte (nicht existierende) Zustell-Id; nur fuer Diagnostik, nicht fuer die Anzeige. */
    public Long getDeliveryId() {
        return deliveryId;
    }
}
