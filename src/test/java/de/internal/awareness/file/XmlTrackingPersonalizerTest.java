package de.internal.awareness.file;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Individualisierung einer XML-Versandkopie: der Trainingslink wird NUR als normaler Textwert in
 * {@code <trainingLink>} eingefuegt; weiterhin kein DOCTYPE/keine Entity, wohlgeformt und ohne externe
 * Aufloesung; das Original bleibt unveraendert.
 */
class XmlTrackingPersonalizerTest {

    private static final String URL = "https://training.example.invalid/t/AbC-_0123456789ABCDEFabcdefghij";

    private final XmlGenerator generator = new XmlGenerator();
    private final XmlTrackingPersonalizer personalizer = new XmlTrackingPersonalizer();

    private byte[] original() {
        return generator.generate("daten", "Titel", "Inhalt");
    }

    private static String asString(byte[] xml) {
        return new String(xml, StandardCharsets.UTF_8);
    }

    private static Document parseHardened(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    @Test
    void addsTrainingLinkAsPlainTextElement() {
        String xml = asString(personalizer.withTrainingLink(original(), URL));
        assertThat(xml).contains("<trainingLink>" + URL + "</trainingLink>");
    }

    @Test
    void containsNoDoctypeOrEntity() {
        String xml = asString(personalizer.withTrainingLink(original(), URL));
        assertThat(xml).doesNotContain("<!DOCTYPE").doesNotContain("<!ENTITY");
    }

    @Test
    void isWellFormedAndSelfContained() throws Exception {
        Document doc = parseHardened(personalizer.withTrainingLink(original(), URL));
        assertThat(doc.getDocumentElement().getNodeName()).isEqualTo("daten");
        NodeList links = doc.getElementsByTagName("trainingLink");
        assertThat(links.getLength()).isEqualTo(1);
        assertThat(links.item(0).getTextContent()).isEqualTo(URL);
    }

    @Test
    void originalBytesAreNotModified() {
        byte[] original = original();
        byte[] snapshot = original.clone();
        personalizer.withTrainingLink(original, URL);
        assertThat(original).isEqualTo(snapshot);
    }
}
