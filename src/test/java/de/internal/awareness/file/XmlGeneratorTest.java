package de.internal.awareness.file;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sicherheits- und Korrektheitstests fuer {@link XmlGenerator}: wohlgeformtes UTF-8-XML mit Deklaration,
 * KEINE DOCTYPE/DTD/externe Entity, korrektes Escaping von Benutzereingaben.
 */
class XmlGeneratorTest {

    private final XmlGenerator generator = new XmlGenerator();

    private static String asString(byte[] xml) {
        return new String(xml, StandardCharsets.UTF_8);
    }

    /** Streng gehaerteter Parser: keine DTDs/DOCTYPE, keine externen Entities. */
    private static Document parseHardened(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml));
    }

    @Test
    void hasUtf8DeclarationAndElements() {
        String xml = asString(generator.generate("daten", "Ein Titel", "Ein Inhalt"));
        assertThat(xml).startsWith("<?xml");
        assertThat(xml).containsIgnoringCase("encoding=\"UTF-8\"");
        assertThat(xml).contains("<daten>").contains("</daten>");
        assertThat(xml).contains("<title>Ein Titel</title>");
        assertThat(xml).contains("<content>Ein Inhalt</content>");
    }

    @Test
    void isWellFormedAndParsesWithHardenedParser() throws Exception {
        Document doc = parseHardened(generator.generate("export", "T", "C"));
        assertThat(doc.getDocumentElement().getNodeName()).isEqualTo("export");
    }

    @Test
    void containsNoDoctypeOrDtd() {
        String xml = asString(generator.generate("daten", "<!DOCTYPE evil>", "kein DOCTYPE bitte"));
        assertThat(xml).doesNotContain("<!DOCTYPE");
        assertThat(xml).doesNotContain("<!ENTITY");
        assertThat(xml).doesNotContain("<!--");
    }

    @Test
    void escapesUserInput() {
        String xml = asString(generator.generate("daten",
                "<script>alert(1)</script>", "Tom & Jerry < 5 > 3 \"x\""));
        // Rohe Markup-Zeichen der Eingabe duerfen NICHT unescaped erscheinen.
        assertThat(xml).doesNotContain("<script>");
        assertThat(xml).contains("&lt;script&gt;");
        assertThat(xml).contains("Tom &amp; Jerry");
        assertThat(xml).contains("&lt; 5 &gt; 3");
    }

    @Test
    void doesNotResolveEntityLikeUserText() {
        // Eine Entity-artige Benutzereingabe wird als reiner (escapeter) Text behandelt, nie aufgeloest.
        String xml = asString(generator.generate("daten", "T", "&xxe; &amp; test"));
        assertThat(xml).contains("&amp;xxe;");
        assertThat(xml).doesNotContain("<!ENTITY");
    }

    @Test
    void invalidRootNameFallsBackToDefault() {
        assertThat(asString(generator.generate("1 ungueltig!", "T", "C"))).contains("<document>");
        assertThat(asString(generator.generate("", "T", "C"))).contains("<document>");
        assertThat(asString(generator.generate(null, "T", "C"))).contains("<document>");
        // "xml"-Praefix ist reserviert -> Fallback.
        assertThat(asString(generator.generate("xmlThing", "T", "C"))).contains("<document>");
    }

    @Test
    void acceptsValidRootName() {
        assertThat(asString(generator.generate("Rechnungen", "T", "C"))).contains("<Rechnungen>");
    }

    @Test
    void stripsXmlIncompatibleControlCharactersAndStaysWellFormed() throws Exception {
        byte[] xml = generator.generate("daten", "A\u0000B\u0008C", "X\u0001Y\u000BZ");
        String s = asString(xml);
        // In XML 1.0 unzulaessige Steuerzeichen entfernt, Nutztext bleibt erhalten.
        assertThat(s).contains("ABC").contains("XYZ");
        assertThat(s).doesNotContain("\u0000").doesNotContain("\u0008").doesNotContain("\u000B");
        // Trotz boesartiger Eingabe weiterhin wohlgeformt.
        Document doc = parseHardened(xml);
        assertThat(doc.getDocumentElement().getNodeName()).isEqualTo("daten");
    }

    @Test
    void implementsFileContentGeneratorForXmlWithDefaultRoot() throws Exception {
        FileContentGenerator contentGenerator = generator;
        assertThat(contentGenerator.type()).isEqualTo(GeneratedFileType.XML);

        byte[] xml = contentGenerator.generate(
                new FileContentRequest("AnfrageTitel", "NichtImXml", "AnfrageText"));
        String s = asString(xml);
        assertThat(s).startsWith("<?xml");
        assertThat(s).contains("<title>AnfrageTitel</title>").contains("<content>AnfrageText</content>");
        // Das XML-Format kennt keinen Untertitel - er wird bewusst ignoriert.
        assertThat(s).doesNotContain("NichtImXml");
        Document doc = parseHardened(xml);
        assertThat(doc.getDocumentElement().getNodeName()).isEqualTo("document");
    }

    @Test
    void requestBasedGenerateRejectsNullRequest() {
        assertThatThrownBy(() -> generator.generate((FileContentRequest) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Kein Inhalt.");
    }
}
