package de.internal.awareness.mail;

import java.util.List;

/**
 * Eingabe fuer einen Versandvorgang des globalen E-Mail-Composers.
 *
 * <p>Der Absender ist bewusst NICHT Teil dieser Anfrage: er kommt ausschliesslich aus der Konfiguration
 * ({@code app.mail.default-sender}) und kann vom Formular nicht ueberschrieben werden. Der Anhang wird nur
 * ueber die interne {@code GeneratedFile}-Id referenziert - niemals ueber einen Dateipfad aus dem Request.</p>
 *
 * @param subject            Betreff (Pflicht)
 * @param body               Plaintext-Nachricht (Pflicht; wird nie als Template/Code interpretiert)
 * @param generatedFileId    optionale Id des einzigen Anhangs aus der Dateibibliothek ({@code null} = kein Anhang)
 * @param contactIds         Ids der serverseitig zu validierenden, ausgewaehlten Kontakte
 * @param insertTrackingLink ob je Empfaenger ein individueller, sichtbarer Trainingslink in Text (und ggf.
 *                           Anhang) eingefuegt wird. Jede Zustellung erhaelt unabhaengig davon eine eigene
 *                           Tracking-Identitaet; dieses Flag steuert nur das Einfuegen des sichtbaren Links.
 */
public record MailSendRequest(String subject, String body, Long generatedFileId, List<Long> contactIds,
                              boolean insertTrackingLink) {
}
