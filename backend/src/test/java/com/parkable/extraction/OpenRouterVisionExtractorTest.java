package com.parkable.extraction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Offline tests exercise the response-parsing seam and model-override logic;
 * the network call itself is covered by an opt-in live smoke test (never
 * runs in CI, costs money) — same split as {@link ClaudeVisionExtractorTest}.
 */
class OpenRouterVisionExtractorTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-14T18:04:00Z");
    private static final ImageInput IMAGE =
            new ImageInput(new byte[] {1}, "image/jpeg", Path.of("sign.jpg"));

    private static OpenRouterVisionExtractor extractor(String modelOverride) {
        return new OpenRouterVisionExtractor(
                "test-key-unused", modelOverride, HttpClient.newHttpClient(), Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    private static String validEnvelopeJson() throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/sign.json"));
    }

    @Test
    void blankApiKeyIsRejectedImmediately() {
        assertThatThrownBy(() -> new OpenRouterVisionExtractor(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void noModelOverrideFallsBackToDefault() {
        // Constructing with a null override must not throw and must not
        // require OPENROUTER_MODEL to be set in the real environment.
        OpenRouterVisionExtractor withDefault = extractor(null);

        assertThat(withDefault).isNotNull();
    }

    @Test
    void parsesBareJsonResponse() throws IOException {
        ExtractionResult result = extractor(null).parseResponse(validEnvelopeJson(), IMAGE);

        assertThat(result).isInstanceOf(ExtractionResult.Success.class);
        ExtractionResult.Success success = (ExtractionResult.Success) result;
        assertThat(success.envelope().rules()).hasSize(1);
        assertThat(success.metadata().parserVersion()).isEqualTo(OpenRouterVisionExtractor.PARSER_VERSION);
        assertThat(success.metadata().extractedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void toleratesMarkdownFencesAndProseAroundJson() throws IOException {
        String fenced = "Here is the extraction:\n```json\n" + validEnvelopeJson() + "\n```\nDone.";

        ExtractionResult result = extractor(null).parseResponse(fenced, IMAGE);

        assertThat(result).isInstanceOf(ExtractionResult.Success.class);
    }

    @Test
    void proseWithoutJsonBecomesNeedsReview() {
        ExtractionResult result = extractor(null).parseResponse("I cannot read this sign clearly.", IMAGE);

        assertThat(result).isInstanceOf(ExtractionResult.NeedsReview.class);
        assertThat(((ExtractionResult.NeedsReview) result).details()).isNotEmpty();
    }

    @Test
    void malformedJsonBecomesNeedsReview() {
        ExtractionResult result = extractor(null).parseResponse("{\"extraction_id\": \"oops\", ", IMAGE);

        assertThat(result).isInstanceOf(ExtractionResult.NeedsReview.class);
    }

    /**
     * Live smoke test — opt in with OPENROUTER_LIVE_TEST=1 (requires
     * OPENROUTER_API_KEY). Verifies the request shape against the real API
     * with the tiny placeholder image; any structured outcome is acceptable.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "OPENROUTER_LIVE_TEST", matches = "1")
    void liveExtractionReturnsAResult() throws IOException {
        Path sign = Path.of("src/test/resources/fixtures/sign.jpg");
        ImageInput image = new ImageInput(Files.readAllBytes(sign), "image/jpeg", sign);

        ExtractionResult result = new OpenRouterVisionExtractor(System.getenv("OPENROUTER_API_KEY")).extract(image);

        assertThat(result).isNotNull();
    }
}
