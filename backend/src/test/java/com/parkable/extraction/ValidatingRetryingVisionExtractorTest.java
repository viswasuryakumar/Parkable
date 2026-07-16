package com.parkable.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkable.extraction.dto.ExtractionEnvelope;
import com.parkable.validation.RuleJsonSchemaValidator;
import com.parkable.validation.SemanticRuleValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ValidatingRetryingVisionExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExtractionMetadata METADATA =
            new ExtractionMetadata("sign.jpg", "mock-v1", Instant.parse("2026-07-14T18:04:00Z"));
    private static final ImageInput IMAGE =
            new ImageInput(new byte[] {1}, "image/jpeg", Path.of("sign.jpg"));

    private final VisionExtractor delegate = mock(VisionExtractor.class);
    private final ValidatingRetryingVisionExtractor extractor = new ValidatingRetryingVisionExtractor(
            delegate, new RuleJsonSchemaValidator(), new SemanticRuleValidator());

    private static ExtractionResult.Success validSuccess() throws IOException {
        JsonNode rawJson = MAPPER.readTree(
                Files.readString(Path.of("src/test/resources/fixtures/sign.json")));
        return new ExtractionResult.Success(
                MAPPER.treeToValue(rawJson, ExtractionEnvelope.class), rawJson, METADATA);
    }

    private static ExtractionResult.Success schemaInvalidSuccess() throws IOException {
        ObjectNode rawJson = (ObjectNode) MAPPER.readTree(
                Files.readString(Path.of("src/test/resources/fixtures/sign.json")));
        ((ObjectNode) rawJson.get("rules").get(0)).remove("type");
        return new ExtractionResult.Success(
                MAPPER.treeToValue(rawJson, ExtractionEnvelope.class), rawJson, METADATA);
    }

    @Test
    void validFirstAttemptPassesThroughWithoutRetry() throws IOException {
        ExtractionResult.Success valid = validSuccess();
        when(delegate.extract(IMAGE)).thenReturn(valid);

        ExtractionResult result = extractor.extract(IMAGE);

        assertThat(result).isSameAs(valid);
        verify(delegate, times(1)).extract(IMAGE);
    }

    @Test
    void invalidFirstAttemptRetriesOnceAndSucceeds() throws IOException {
        ExtractionResult.Success valid = validSuccess();
        when(delegate.extract(IMAGE)).thenReturn(schemaInvalidSuccess(), valid);

        ExtractionResult result = extractor.extract(IMAGE);

        assertThat(result).isSameAs(valid);
        verify(delegate, times(2)).extract(IMAGE);
    }

    @Test
    void twoInvalidAttemptsBecomeNeedsReviewWithHonestMessage() throws IOException {
        when(delegate.extract(IMAGE)).thenReturn(schemaInvalidSuccess(), schemaInvalidSuccess());

        ExtractionResult result = extractor.extract(IMAGE);

        assertThat(result).isInstanceOf(ExtractionResult.NeedsReview.class);
        ExtractionResult.NeedsReview review = (ExtractionResult.NeedsReview) result;
        // User-facing message stays generic; specifics live in details for logs.
        assertThat(review.message()).isEqualTo(ValidatingRetryingVisionExtractor.RETAKE_MESSAGE);
        assertThat(review.details()).anySatisfy(d -> assertThat(d).startsWith("attempt 1: "));
        assertThat(review.details()).anySatisfy(d -> assertThat(d).startsWith("attempt 2: "));
        verify(delegate, times(2)).extract(IMAGE);
    }

    @Test
    void delegateNeedsReviewThenValidSuccessRecovers() throws IOException {
        ExtractionResult.Success valid = validSuccess();
        when(delegate.extract(IMAGE)).thenReturn(
                new ExtractionResult.NeedsReview("not parseable", List.of("garbled"), METADATA), valid);

        ExtractionResult result = extractor.extract(IMAGE);

        assertThat(result).isSameAs(valid);
        verify(delegate, times(2)).extract(IMAGE);
    }

    @Test
    void semanticallyInvalidEnvelopeAlsoTriggersRetry() throws IOException {
        // Schema-valid but semantically broken: window start equals end.
        ObjectNode rawJson = (ObjectNode) MAPPER.readTree(
                Files.readString(Path.of("src/test/resources/fixtures/sign.json")));
        ObjectNode window = (ObjectNode) rawJson.get("rules").get(0).get("time_windows").get(0);
        window.put("end_time", "08:00");
        window.put("crosses_midnight", false);
        ExtractionResult.Success semanticallyInvalid = new ExtractionResult.Success(
                MAPPER.treeToValue(rawJson, ExtractionEnvelope.class), rawJson, METADATA);
        when(delegate.extract(IMAGE)).thenReturn(semanticallyInvalid, semanticallyInvalid);

        ExtractionResult result = extractor.extract(IMAGE);

        assertThat(result).isInstanceOf(ExtractionResult.NeedsReview.class);
        assertThat(((ExtractionResult.NeedsReview) result).details())
                .anySatisfy(d -> assertThat(d).contains("start equals end"));
    }
}
