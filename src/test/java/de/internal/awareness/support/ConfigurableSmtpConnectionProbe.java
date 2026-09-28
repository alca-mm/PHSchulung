package de.internal.awareness.support;

import de.internal.awareness.system.SmtpConnectionProbe;
import jakarta.mail.MessagingException;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-Double fuer {@link SmtpConnectionProbe}: verbindet NIE zu einem echten SMTP-Server und versendet
 * KEINE E-Mail. Steuerbar ueber {@link #succeed()} bzw. {@link #failWith(MessagingException)}, um Erfolg
 * oder eine bestimmte Fehlerkategorie deterministisch zu simulieren. Zaehlt die Verbindungsaufrufe mit.
 */
public class ConfigurableSmtpConnectionProbe implements SmtpConnectionProbe {

    // Wenn gesetzt, wird diese Ausnahme geworfen; null bedeutet Erfolg.
    private volatile MessagingException toThrow;
    private final AtomicInteger connectCount = new AtomicInteger();

    /** Naechster Verbindungsversuch ist erfolgreich (kein Fehler). */
    public void succeed() {
        this.toThrow = null;
    }

    /** Naechster Verbindungsversuch schlaegt mit der angegebenen Ausnahme fehl. */
    public void failWith(MessagingException ex) {
        this.toThrow = ex;
    }

    /** Anzahl der bisherigen Verbindungsaufrufe. */
    public int connectCount() {
        return connectCount.get();
    }

    @Override
    public void connect() throws MessagingException {
        connectCount.incrementAndGet();
        MessagingException ex = this.toThrow;
        if (ex != null) {
            throw ex;
        }
    }
}
