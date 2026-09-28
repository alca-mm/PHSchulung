package de.internal.awareness.file;

import de.internal.awareness.config.FileStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reine Unit-Tests der Generator-Auswahl (Dispatch) in {@link GeneratedFileService} - ohne Spring-Kontext,
 * mit einfachen Fake-Generatoren. Prueft die Fail-fast-Regeln beim Aufbau (doppelter Generator, fehlender
 * Generator fuer einen {@link GeneratedFileType}, Generator ohne Typ), die fail-closed-Ablehnung eines
 * fehlenden Typs sowie die korrekte Weiterleitung an genau den Generator des angeforderten Typs.
 */
class GeneratedFileServiceDispatchTest {

    @TempDir
    Path storageDir;

    /** Einfacher Fake-Generator: merkt sich die Anfragen und liefert typabhaengige Dummy-Bytes. */
    private static final class FakeGenerator implements FileContentGenerator {

        private final GeneratedFileType type;
        private final List<FileContentRequest> requests = new ArrayList<>();

        FakeGenerator(GeneratedFileType type) {
            this.type = type;
        }

        @Override
        public GeneratedFileType type() {
            return type;
        }

        @Override
        public byte[] generate(FileContentRequest request) {
            requests.add(request);
            return ("fake-" + type.name()).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static List<FileContentGenerator> fakesFor(GeneratedFileType... types) {
        List<FileContentGenerator> generators = new ArrayList<>();
        for (GeneratedFileType type : types) {
            generators.add(new FakeGenerator(type));
        }
        return generators;
    }

    private static List<FileContentGenerator> fakesForAllTypes() {
        return fakesFor(GeneratedFileType.values());
    }

    private static FakeGenerator fakeOf(List<FileContentGenerator> generators, GeneratedFileType type) {
        return generators.stream()
                .filter(g -> g.type() == type)
                .map(FakeGenerator.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private FileStorageService realStorage() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setGeneratedDir(storageDir.toString());
        return new FileStorageService(properties);
    }

    private static GeneratedFileRepository echoRepository() {
        GeneratedFileRepository repository = mock(GeneratedFileRepository.class);
        when(repository.saveAndFlush(any(GeneratedFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return repository;
    }

    @Test
    void constructsWhenEveryTypeHasExactlyOneGenerator() {
        GeneratedFileService service = new GeneratedFileService(mock(GeneratedFileRepository.class),
                realStorage(), new XmlGenerator(), fakesForAllTypes());

        assertThat(service).isNotNull();
    }

    @Test
    void duplicateGeneratorForSameTypeFailsFast() {
        List<FileContentGenerator> generators = fakesForAllTypes();
        generators.add(new FakeGenerator(GeneratedFileType.PDF));

        assertThatThrownBy(() -> new GeneratedFileService(mock(GeneratedFileRepository.class), realStorage(),
                new XmlGenerator(), generators))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PDF");
    }

    @ParameterizedTest
    @EnumSource(GeneratedFileType.class)
    void missingGeneratorForAnyTypeFailsFast(GeneratedFileType missing) {
        GeneratedFileType[] others = Arrays.stream(GeneratedFileType.values())
                .filter(t -> t != missing)
                .toArray(GeneratedFileType[]::new);

        assertThatThrownBy(() -> new GeneratedFileService(mock(GeneratedFileRepository.class), realStorage(),
                new XmlGenerator(), fakesFor(others)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(missing.name());
    }

    @Test
    void emptyGeneratorListFailsFast() {
        assertThatThrownBy(() -> new GeneratedFileService(mock(GeneratedFileRepository.class), realStorage(),
                new XmlGenerator(), List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void generatorWithoutTypeFailsFast() {
        List<FileContentGenerator> generators = fakesForAllTypes();
        generators.add(new FakeGenerator(null));

        assertThatThrownBy(() -> new GeneratedFileService(mock(GeneratedFileRepository.class), realStorage(),
                new XmlGenerator(), generators))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createWithNullTypeIsRejectedWithoutSideEffects() throws Exception {
        GeneratedFileRepository repository = mock(GeneratedFileRepository.class);
        List<FileContentGenerator> generators = fakesForAllTypes();
        GeneratedFileService service = new GeneratedFileService(repository, realStorage(),
                new XmlGenerator(), generators);

        assertThatThrownBy(() -> service.create(null, "Anzeige", "datei",
                new FileContentRequest("T", "U", "Text")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Dateityp fehlt.");

        verify(repository, never()).saveAndFlush(any(GeneratedFile.class));
        for (FileContentGenerator generator : generators) {
            assertThat(((FakeGenerator) generator).requests).isEmpty();
        }
        try (var files = Files.list(storageDir)) {
            assertThat(files).isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(GeneratedFileType.class)
    void createDispatchesToExactlyTheGeneratorOfTheRequestedType(GeneratedFileType type) {
        List<FileContentGenerator> generators = fakesForAllTypes();
        FileStorageService storage = realStorage();
        GeneratedFileService service = new GeneratedFileService(echoRepository(), storage,
                new XmlGenerator(), generators);
        FileContentRequest request = new FileContentRequest("Titel", "Untertitel", "Text");

        GeneratedFile file = service.create(type, "Anzeige", "bericht", request);

        assertThat(file.getFileType()).isEqualTo(type);
        assertThat(file.getContentType()).isEqualTo(type.contentType());
        assertThat(file.getDownloadFilename()).isEqualTo("bericht." + type.extension());
        assertThat(storage.read(file.getStoredFilename()))
                .isEqualTo(("fake-" + type.name()).getBytes(StandardCharsets.UTF_8));
        for (FileContentGenerator generator : generators) {
            FakeGenerator fake = (FakeGenerator) generator;
            if (fake.type() == type) {
                assertThat(fake.requests).containsExactly(request);
            } else {
                assertThat(fake.requests).as("Generator %s darf nicht aufgerufen werden", fake.type()).isEmpty();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(GeneratedFileType.class)
    void invalidNamesAreRejectedBeforeAnyGeneratorRuns(GeneratedFileType type) throws Exception {
        // Guenstige Eingabepruefungen (Anzeigename, Dateiname) laufen VOR der ggf. aufwendigen Generierung.
        GeneratedFileRepository repository = mock(GeneratedFileRepository.class);
        List<FileContentGenerator> generators = fakesForAllTypes();
        GeneratedFileService service = new GeneratedFileService(repository, realStorage(),
                new XmlGenerator(), generators);
        FileContentRequest request = new FileContentRequest("T", "U", "Text");

        assertThatThrownBy(() -> service.create(type, "  ", "datei", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Anzeigename fehlt.");
        assertThatThrownBy(() -> service.create(type, "Anzeige", "../../etc/passwd", request))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).saveAndFlush(any(GeneratedFile.class));
        for (FileContentGenerator generator : generators) {
            assertThat(((FakeGenerator) generator).requests)
                    .as("Generator %s darf bei ungueltigen Namen nicht aufgerufen werden", generator.type())
                    .isEmpty();
        }
        try (var files = Files.list(storageDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void createDocxDelegatesToDocxGeneratorWithAllFields() {
        List<FileContentGenerator> generators = fakesForAllTypes();
        GeneratedFileService service = new GeneratedFileService(echoRepository(), realStorage(),
                new XmlGenerator(), generators);

        GeneratedFile file = service.createDocx("Anzeige", "rechnung", "Titel", "Untertitel", "Text");

        assertThat(file.getFileType()).isEqualTo(GeneratedFileType.DOCX);
        assertThat(file.getDownloadFilename()).isEqualTo("rechnung.docx");
        assertThat(fakeOf(generators, GeneratedFileType.DOCX).requests)
                .containsExactly(new FileContentRequest("Titel", "Untertitel", "Text"));
    }

    @Test
    void createXmlKeepsRootNameAndDoesNotUseGenericXmlGenerator() {
        List<FileContentGenerator> generators = fakesForAllTypes();
        FileStorageService storage = realStorage();
        GeneratedFileService service = new GeneratedFileService(echoRepository(), storage,
                new XmlGenerator(), generators);

        GeneratedFile file = service.createXml("Anzeige", "daten", "export", "Titel", "Inhalt");

        String xml = new String(storage.read(file.getStoredFilename()), StandardCharsets.UTF_8);
        assertThat(xml).startsWith("<?xml").contains("<export>").contains("Inhalt");
        assertThat(fakeOf(generators, GeneratedFileType.XML).requests).isEmpty();
    }
}
