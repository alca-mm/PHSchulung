package de.internal.awareness.file;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Erzeugt eine individualisierte KOPIE eines bestehenden XML (im Speicher), in der der Trainingslink als
 * NORMALER Textwert in einem klar benannten Element {@code <trainingLink>...</trainingLink>} ergaenzt wird.
 *
 * <p>Sicherheitsgrenze (verbindlich): Das Original der Bibliothek wird NICHT veraendert. Es wird streng
 * gehaertet geparst (kein DOCTYPE/DTD, keine externen Entities, kein XInclude, keine externe Aufloesung) und
 * ohne DOCTYPE serialisiert; die URL wird ausschliesslich als (korrekt escapeter) Textinhalt eingefuegt -
 * KEINE externe Entity, KEIN XInclude, KEINE Remote-Referenz.</p>
 */
@Component
public class XmlTrackingPersonalizer {

    /** Name des Elements, das den sichtbaren Trainingslink als reinen Textwert traegt. */
    static final String LINK_ELEMENT = "trainingLink";

    /**
     * Baut aus {@code originalXml} eine neue XML-Kopie mit einem zusaetzlichen {@code <trainingLink>}-Element
     * (Textwert = URL) direkt unter dem Wurzelelement.
     *
     * @param originalXml die unveraenderten Original-Bytes (bleiben unveraendert)
     * @param url         der individuelle, bereits validierte Trainingslink
     * @return die individualisierte Kopie als neues Byte-Array (UTF-8)
     */
    public byte[] withTrainingLink(byte[] originalXml, String url) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(originalXml));
            Element link = document.createElement(LINK_ELEMENT);
            link.setTextContent(url); // wird bei der Serialisierung korrekt escaped
            document.getDocumentElement().appendChild(link);

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            // Es wird bewusst KEIN DOCTYPE ausgegeben.

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Individualisierte XML-Kopie konnte nicht erzeugt werden.", e);
        }
    }
}
