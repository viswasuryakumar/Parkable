package com.parkable.extraction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureVisionExtractorTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    private final FixtureVisionExtractor extractor = new FixtureVisionExtractor();

    private static ImageInput imageAt(Path path) throws IOException {
        return new ImageInput(Files.readAllBytes(path), "image/jpeg", path);
    }

    @Test
    void loadsSiblingJsonFixtureAsSuccess() throws IOException {
        ExtractionResult result = extractor.extract(imageAt(FIXTURES.resolve("sign.jpg")));

        assertThat(result).isInstanceOf(ExtractionResult.Success.class);
        ExtractionResult.Success success = (ExtractionResult.Success) result;
        assertThat(success.envelope().extractionId()).isEqualTo("fixture-0001");
        assertThat(success.envelope().rules()).hasSize(1);
        assertThat(success.envelope().rules().getFirst().type()).isEqualTo("no_parking");
        assertThat(success.rawJson().get("confidence").asDouble()).isEqualTo(0.97);
    }

    @Test
    void metadataCarriesReproducibilityContract() throws IOException {
        Path image = FIXTURES.resolve("sign.jpg");
        ExtractionResult result = extractor.extract(imageAt(image));

        ExtractionMetadata metadata = result.metadata();
        assertThat(metadata.photoReference()).isEqualTo(image.toString());
        assertThat(metadata.parserVersion()).isEqualTo(FixtureVisionExtractor.PARSER_VERSION);
        // Deterministic: taken from the fixture's own ingestion_timestamp, not a wall clock.
        assertThat(metadata.extractedAt()).isEqualTo(Instant.parse("2026-07-14T18:04:00Z"));
    }

    @Test
    void malformedJsonBecomesNeedsReviewNotException() throws IOException {
        ExtractionResult result = extractor.extract(imageAt(FIXTURES.resolve("malformed.jpg")));

        assertThat(result).isInstanceOf(ExtractionResult.NeedsReview.class);
        assertThat(((ExtractionResult.NeedsReview) result).details()).isNotEmpty();
    }

    @Test
    void missingFixtureIsASetupErrorNotARetakePrompt() {
        Path phantom = FIXTURES.resolve("no-such-image.jpg");
        ImageInput image = new ImageInput(new byte[] {1}, "image/jpeg", phantom);

        assertThatThrownBy(() -> extractor.extract(image))
                .isInstanceOf(VisionExtractionException.class)
                .hasMessageContaining("no-such-image.json");
    }
}
