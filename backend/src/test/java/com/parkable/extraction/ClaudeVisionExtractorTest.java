package com.parkable.extraction;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline tests exercise the response-parsing seam; the network call itself
 * is covered by an opt-in live smoke test (never runs in CI, costs money).
 */
class ClaudeVisionExtractorTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-14T18:04:00Z");
    private static final ImageInput IMAGE =
            new ImageInput(new byte[] {1}, "image/jpeg", Path.of("sign.jpg"));

    private static ClaudeVisionExtractor offlineExtractor() {
        // Never issues requests in these tests; the key just satisfies client construction.
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey("test-key-unused").build();
        return new ClaudeVisionExtractor(client, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    private static String validEnvelopeJson() throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/sign.json"));
    }

    @Test
    void parsesBareJsonResponse() throws IOException {
        ExtractionResult result = offlineExtractor().parseResponse(validEnvelopeJson(), IMAGE);

        assertThat(result).isInstanceOf(ExtractionResult.Success.class);
        ExtractionResult.Success success = (ExtractionResult.Success) result;
        assertThat(success.envelope().rules()).hasSize(1);
        assertThat(success.metadata().parserVersion()).isEqualTo(ClaudeVisionExtractor.PARSER_VERSION);
        assertThat(success.metadata().extractedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void toleratesMarkdownFencesAndProseAroundJson() throws IOException {
        String fenced = "Here is the extraction:\n```json\n" + validEnvelopeJson() + "\n```\nDone.";

        ExtractionResult result = offlineExtractor().parseResponse(fenced, IMAGE);

        assertThat(result).isInstanceOf(ExtractionResult.Success.class);
    }

    @Test
    void proseWithoutJsonBecomesNeedsReview() {
        ExtractionResult result = offlineExtractor()
                .parseResponse("I cannot read this sign clearly.", IMAGE);

        assertThat(result).isInstanceOf(ExtractionResult.NeedsReview.class);
        assertThat(((ExtractionResult.NeedsReview) result).details()).isNotEmpty();
    }

    @Test
    void malformedJsonBecomesNeedsReview() {
        ExtractionResult result = offlineExtractor()
                .parseResponse("{\"extraction_id\": \"oops\", ", IMAGE);

        assertThat(result).isInstanceOf(ExtractionResult.NeedsReview.class);
    }

    /**
     * Live smoke test — opt in with ANTHROPIC_LIVE_TEST=1 (requires
     * ANTHROPIC_API_KEY). Verifies the request shape against the real API
     * with the tiny placeholder image; any structured outcome is acceptable.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_LIVE_TEST", matches = "1")
    void liveExtractionReturnsAResult() throws IOException {
        Path sign = Path.of("src/test/resources/fixtures/sign.jpg");
        ImageInput image = new ImageInput(Files.readAllBytes(sign), "image/jpeg", sign);

        ExtractionResult result = new ClaudeVisionExtractor().extract(image);

        assertThat(result).isNotNull();
    }
}
