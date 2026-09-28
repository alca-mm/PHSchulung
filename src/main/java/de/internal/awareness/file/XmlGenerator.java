package de.internal.awareness.file;

import org.springframework.stereotype.Component;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Erzeugt sichere, vollstaendig selbstenthaltende XML-Dateien ueber die StAX-API ({@link XMLStreamWriter}).
 *
 * <p>Sicherheitsgrenze (verbindlich): Die Datei ist rein passiv.</p>
 * <ul>
 *   <li>UTF-8 mit XML-Deklaration.</li>
 *   <li>KEINE {@code DOCTYPE}-Deklaration, KEINE (externen) Entities, KEINE DTD - der StAX-Writer schreibt
 *       so etwas grundsaetzlich nicht; es findet keinerlei externe Aufloesung statt.</li>
 *   <li>Benutzereingaben werden ausschliesslich als Zeichendaten geschrieben und dabei korrekt escaped
 *       ({@code &}, {@code <}, {@code >} etc.) - keine String-Konkatenation von Roh-XML.</li>
 * </ul>
 *
 * <p>Als {@link FileContentGenerator} fuer {@link GeneratedFileType#XML} registriert. Der generische Einstieg
 * {@link #generate(FileContentRequest)} kennt keinen Root-Namen und nutzt daher das Standard-Wurzelelement
 * {@code document}; ein Untertitel wird im XML-Format bewusst ignoriert. Die Oberflaeche nutzt fuer XML weiterhin
 * {@link #generate(String, String, String)} mit dem gewuenschten Root-Namen.</p>
 */
@Component
public class XmlGenerator implements FileContentGenerator {

    /** Fallback-Name, falls der gewuenschte Root-Name kein gueltiger XML-Elementname ist. */
    private static final String DEFAULT_ROOT = "document";

    /** Gueltiger (vereinfachter) XML-Elementname: Buchstabe/Unterstrich, dann Buchstaben/Ziffern/-/_/Punkt. */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9._-]*");

    /** Dieser Generator erzeugt {@link GeneratedFileType#XML}. */
    @Override
    public GeneratedFileType type() {
        return GeneratedFileType.XML;
    }

    /**
     * Generischer Einstieg ueber das neutrale Eingabemodell: erzeugt das XML mit dem Standard-Wurzelelement
     * ({@code document}), Titel und Inhalt; der Untertitel wird ignoriert (im XML-Format nicht vorgesehen).
     *
     * @throws IllegalArgumentException wenn {@code request} {@code null} ist
     */
    @Override
    public byte[] generate(FileContentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Kein Inhalt.");
        }
        return generate(null, request.title(), request.content());
    }

    /**
     * Baut ein selbstenthaltendes XML-Dokument der Form
     * {@code <root><title>...</title><content>...</content></root>} und gibt es als UTF-8-Byte-Array zurueck.
     *
     * @param rootName gewuenschter Name des Wurzelelements (wird bereinigt; ungueltig -&gt; {@code document})
     * @param title    Titel-Inhalt (wird escaped; {@code null} -&gt; leer)
     * @param content  Text-Inhalt (wird escaped; {@code null} -&gt; leer)
     * @return das erzeugte, passive XML als Byte-Array (UTF-8)
     */
    public byte[] generate(String rootName, String title, String content) {
        String root = sanitizeRootName(rootName);
        XMLOutputFactory factory = XMLOutputFactory.newFactory();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XMLStreamWriter writer = factory.createXMLStreamWriter(out, StandardCharsets.UTF_8.name());
            try {
                writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
                writer.writeStartElement(root);

                writer.writeStartElement("title");
                writer.writeCharacters(safe(title));
                writer.writeEndElement();

                writer.writeStartElement("content");
                writer.writeCharacters(safe(content));
                writer.writeEndElement();

                writer.writeEndElement();
                writer.writeEndDocument();
                writer.flush();
            } finally {
                writer.close();
            }
            return out.toByteArray();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("XML konnte nicht erzeugt werden.", e);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("XML konnte nicht erzeugt werden.", e);
        }
    }

    /** Leerer String statt {@code null}, ohne in XML 1.0 unzulaessige Steuerzeichen (Wohlgeformtheit sichern). */
    private static String safe(String value) {
        return value == null ? "" : DocumentText.stripXmlIncompatibleChars(value);
    }

    /** Liefert einen gueltigen XML-Elementnamen: gewuenschter Name, sofern gueltig, sonst {@link #DEFAULT_ROOT}. */
    private static String sanitizeRootName(String rootName) {
        if (rootName == null) {
            return DEFAULT_ROOT;
        }
        String trimmed = rootName.trim();
        // "xml" (case-insensitive) ist als Elementname-Praefix reserviert -> ablehnen und Fallback verwenden.
        if (VALID_NAME.matcher(trimmed).matches()
                && !trimmed.regionMatches(true, 0, "xml", 0, 3)) {
            return trimmed;
        }
        return DEFAULT_ROOT;
    }
}
