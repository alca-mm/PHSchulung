package de.internal.awareness.file;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.extractor.XSSFExcelExtractor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Test-Hilfsmethoden: prueft, dass ein erzeugter Datei-Inhalt zum erwarteten {@link GeneratedFileType} passt
 * (Signatur/Format) und - soweit ohne Zusatzbibliothek moeglich - den erwarteten Text enthaelt. Wird von den
 * Dienst- und MVC-Tests der Dateibibliothek gemeinsam genutzt, damit alle Typen gleich streng geprueft werden.
 */
public final class GeneratedContentAssertions {

    private GeneratedContentAssertions() {
    }

    /**
     * Prueft Format-Signatur und Lesbarkeit des Inhalts fuer den Typ: Signatur ({@code %PDF-}/{@code %%EOF},
     * ZIP "PK", XML-Deklaration bzw. striktes UTF-8), Oeffnen mit der passenden Bibliothek (PDFBox bzw. POI) und
     * {@code expectedText} im extrahierten Text.
     */
    public static void assertReadableContent(GeneratedFileType type, byte[] content, String expectedText) {
        assertThat(content).as("Inhalt fuer %s", type).isNotEmpty();
        switch (type) {
            case DOCX -> {
                assertZipSignature(content);
                assertThat(docxText(content)).contains(expectedText);
            }
            case XML -> assertThat(strictUtf8(content)).startsWith("<?xml").contains(expectedText);
            case PDF -> {
                assertThat(new String(content, 0, Math.min(5, content.length), StandardCharsets.US_ASCII))
                        .isEqualTo("%PDF-");
                String tail = new String(content, Math.max(0, content.length - 64), Math.min(64, content.length),
                        StandardCharsets.ISO_8859_1);
                assertThat(tail).contains("%%EOF");
                assertThat(pdfText(content)).contains(expectedText);
            }
            case XLSX -> {
                assertZipSignature(content);
                assertThat(xlsxText(content)).contains(expectedText);
            }
            case PPTX -> {
                assertZipSignature(content);
                assertThat(pptxText(content)).contains(expectedText);
            }
            case TXT, CSV -> assertThat(strictUtf8(content)).contains(expectedText);
            default -> fail("Unbekannter Dateityp im Test: " + type);
        }
        if (type != GeneratedFileType.XML) {
            // Kein stiller XML-Rueckfall: nur der XML-Typ darf ein XML-Dokument liefern.
            assertThat(new String(content, 0, Math.min(5, content.length), StandardCharsets.ISO_8859_1))
                    .as("Inhalt fuer %s darf kein XML sein", type)
                    .isNotEqualTo("<?xml");
        }
    }

    /** ZIP-Signatur "PK" (OOXML-Container). */
    public static void assertZipSignature(byte[] content) {
        assertThat(content.length).isGreaterThanOrEqualTo(2);
        assertThat(content[0]).isEqualTo((byte) 'P');
        assertThat(content[1]).isEqualTo((byte) 'K');
    }

    /** Dekodiert streng als UTF-8 (ungueltige Byte-Folgen fuehren zum Testfehler). */
    public static String strictUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new AssertionError("Inhalt ist kein gueltiges UTF-8.", e);
        }
    }

    /** Oeffnet das PDF mit PDFBox und liefert den Text aller Seiten. */
    public static String pdfText(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Oeffnet das DOCX mit POI und liefert den Text. */
    public static String docxText(byte[] content) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Oeffnet die Arbeitsmappe mit POI (XSSFWorkbook) und liefert den Text aller Zellen. */
    public static String xlsxText(byte[] content) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content));
             XSSFExcelExtractor extractor = new XSSFExcelExtractor(workbook)) {
            return extractor.getText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Oeffnet die Praesentation mit POI (XMLSlideShow) und liefert den Text aller Folien. */
    public static String pptxText(byte[] content) {
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(content));
             SlideShowExtractor<?, ?> extractor = new SlideShowExtractor<>(slideShow)) {
            return extractor.getText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
