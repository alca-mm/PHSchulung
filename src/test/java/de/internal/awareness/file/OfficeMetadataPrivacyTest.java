package de.internal.awareness.file;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Datenschutz- und Ehrlichkeitstest fuer die Dokumenteigenschaften der erzeugten Office-Dateien (DOCX, XLSX,
 * PPTX) in {@code docProps/core.xml} und {@code docProps/app.xml}.
 *
 * <p>Geprueft wird, dass die Eigenschaften KEINE Angaben ueber den erzeugenden Server enthalten (Benutzername,
 * Rechnername, Home-Verzeichnis), keinen {@code lastModifiedBy}-Eintrag besitzen und keine irrefuehrende
 * Herkunft ("Microsoft") behaupten. Fuer PPTX (Grundlage ist eine eingebaute Vorlage mit Altwerten) wird
 * zusaetzlich geprueft, dass Erstellungs-/Aenderungszeitpunkt aktuell sind und die Folienanzahl stimmt.</p>
 *
 * <p>Robustheit: Umgebungswerte werden nur gegen die Textwerte der Eigenschaften (nicht gegen Elementnamen oder
 * Namensraum-URIs) geprueft. Sehr kurze Werte (unter {@value #MIN_SENSITIVE_LENGTH} Zeichen) und Werte, die in
 * legitimen Eigenschaften natuerlich vorkommen (z. B. "POI"), werden uebersprungen, damit der Test nicht durch
 * Zufallstreffer fehlschlaegt.</p>
 */
class OfficeMetadataPrivacyTest {

    private static final FileContentRequest REQUEST =
            new FileContentRequest("Metadatenpruefung", "Untertitel", "Zeile eins\nZeile zwei");

    /** Mindestlaenge, ab der ein Umgebungswert als Leck erkennbar ist (kuerzere Werte: Zufallstreffer). */
    private static final int MIN_SENSITIVE_LENGTH = 3;

    /** Werte, die legitim in den Eigenschaften stehen; ein darin enthaltener Umgebungswert wird nicht geprueft. */
    private static final List<String> BENIGN_VALUES = List.of(
            "apache poi", "metadatenpruefung", "untertitel", "true", "false");

    private static final Pattern ELEMENT_TEXT = Pattern.compile(">([^<>]+)<");

    static Stream<Arguments> officeGenerators() {
        return Stream.of(
                Arguments.of(Named.of("DOCX", new DocxGenerator())),
                Arguments.of(Named.of("XLSX", new XlsxGenerator())),
                Arguments.of(Named.of("PPTX", new PptxGenerator())));
    }

    // ------------------------------------------------------------------------------------------------
    // Alle Office-Formate
    // ------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("officeGenerators")
    void propertiesContainNoUserHostOrHomeInformation(FileContentGenerator generator) throws Exception {
        Map<String, String> docProps = docProps(generator.generate(REQUEST));
        String values = elementTextValues(docProps).toLowerCase(Locale.ROOT);
        assertThat(values).as("Textwerte der Dokumenteigenschaften").isNotBlank();

        for (String sensitive : sensitiveEnvironmentValues()) {
            assertThat(values)
                    .as("Dokumenteigenschaften (%s) duerfen keine Server-Umgebungsangaben enthalten", generator.type())
                    .doesNotContain(sensitive.toLowerCase(Locale.ROOT));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("officeGenerators")
    void propertiesHaveNoLastModifiedByAndNoMicrosoftOrigin(FileContentGenerator generator) throws Exception {
        Map<String, String> docProps = docProps(generator.generate(REQUEST));
        String core = docProps.get("docProps/core.xml");
        String app = docProps.get("docProps/app.xml");

        assertThat(core).doesNotContain("lastModifiedBy");
        assertThat(core.toLowerCase(Locale.ROOT)).doesNotContain("microsoft");
        assertThat(app.toLowerCase(Locale.ROOT)).doesNotContain("microsoft");
        // Einheitliche, ehrliche Herkunftsangabe fuer alle mit Apache POI erzeugten Office-Dateien.
        assertThat(firstElementText(app, "Application")).isEqualTo("Apache POI");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("officeGenerators")
    void propertiesHaveNoCompanyManagerOrTemplateInformation(FileContentGenerator generator) throws Exception {
        String app = docProps(generator.generate(REQUEST)).get("docProps/app.xml");
        assertThat(app).doesNotContain("<Company>")
                .doesNotContain("<Manager>")
                .doesNotContain("<Template>")
                .doesNotContain("<AppVersion>");
    }

    // ------------------------------------------------------------------------------------------------
    // PPTX: aktuelle Zeitpunkte und echte Folienanzahl
    // ------------------------------------------------------------------------------------------------

    @Test
    void pptxTimestampsAreCurrentAndSlideCountIsReal() throws Exception {
        Instant before = Instant.now();
        byte[] pptx = new PptxGenerator().generate(
                new FileContentRequest("Folien", null, "Zeile\n".repeat(20).trim()));

        Map<String, String> docProps = docProps(pptx);
        String core = docProps.get("docProps/core.xml");
        String app = docProps.get("docProps/app.xml");

        Instant created = Instant.parse(firstElementText(core, "dcterms:created"));
        Instant modified = Instant.parse(firstElementText(core, "dcterms:modified"));
        Instant lowerBound = before.minus(Duration.ofMinutes(5));
        Instant upperBound = Instant.now().plus(Duration.ofMinutes(1));
        assertThat(created).isBetween(lowerBound, upperBound);
        assertThat(modified).isBetween(lowerBound, upperBound);

        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(pptx))) {
            int realSlideCount = show.getSlides().size();
            assertThat(realSlideCount).isGreaterThan(1);
            assertThat(firstElementText(app, "Slides")).isEqualTo(Integer.toString(realSlideCount));
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Hilfsfunktionen
    // ------------------------------------------------------------------------------------------------

    /** Liest {@code docProps/core.xml} und {@code docProps/app.xml}; beide muessen vorhanden sein. */
    private static Map<String, String> docProps(byte[] ooxml) throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(ooxml))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().startsWith("docProps/")) {
                    entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        assertThat(entries).containsKeys("docProps/core.xml", "docProps/app.xml");
        return entries;
    }

    /** Alle Element-Textwerte (ohne Markup, Elementnamen und Namensraum-URIs), zeilenweise verbunden. */
    private static String elementTextValues(Map<String, String> docProps) {
        StringBuilder values = new StringBuilder();
        for (String xml : docProps.values()) {
            Matcher matcher = ELEMENT_TEXT.matcher(xml);
            while (matcher.find()) {
                values.append(matcher.group(1).trim()).append('\n');
            }
        }
        return values.toString();
    }

    /** Textinhalt des ersten Elements mit dem gegebenen (ggf. praefixierten) Namen oder {@code null}. */
    private static String firstElementText(String xml, String qualifiedName) {
        Matcher matcher = Pattern.compile("<" + Pattern.quote(qualifiedName) + "(?:\\s[^>]*)?>([^<]*)</"
                + Pattern.quote(qualifiedName) + ">").matcher(xml);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Umgebungswerte des Servers, die nicht in Dokumenteigenschaften auftauchen duerfen: Benutzername,
     * Rechnername (sofern ermittelbar), Home-Verzeichnis und dessen letzter Pfadbestandteil. Zu kurze oder
     * legitim vorkommende Werte werden ausgelassen.
     */
    private static Set<String> sensitiveEnvironmentValues() {
        List<String> candidates = new ArrayList<>();
        candidates.add(System.getProperty("user.name"));
        String home = System.getProperty("user.home");
        candidates.add(home);
        if (home != null && !home.isBlank()) {
            Path lastSegment = Path.of(home).getFileName();
            if (lastSegment != null) {
                candidates.add(lastSegment.toString());
            }
        }
        candidates.add(System.getenv("COMPUTERNAME"));
        candidates.add(System.getenv("HOSTNAME"));
        try {
            candidates.add(InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            // Rechnername nicht ermittelbar (z. B. ohne Namensaufloesung) - diese Pruefung entfaellt dann.
        }

        Set<String> sensitive = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String value = candidate.trim();
            if (value.length() < MIN_SENSITIVE_LENGTH || isBenign(value)) {
                continue;
            }
            sensitive.add(value);
        }
        return sensitive;
    }

    private static boolean isBenign(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return BENIGN_VALUES.stream().anyMatch(benign -> benign.contains(lower))
                || lower.equals("localhost") || lower.matches("[0-9]+");
    }
}
