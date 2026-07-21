package com.parkable.extraction;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.extraction.dto.ExtractionEnvelope;

import java.util.List;

/**
 * Shared model-response parsing for HTTP-based vision extractors: locate the
 * outer JSON object (tolerating prose/markdown fences some models add
 * despite instructions), then map to the extraction envelope.
 *
 * <p>Non-JSON or shape-mismatched output is an EXPECTED failure mode (feeds
 * the retry decorator), never an exception — an unreadable sign is data, not
 * a bug in our code.
 */
final class ExtractionResponseParser {

    private ExtractionResponseParser() {}

    static ExtractionResult parse(String responseText, ObjectMapper mapper, ExtractionMetadata metadata) {
        int start = responseText.indexOf('{');
        int end = responseText.lastIndexOf('}');
        if (start == -1 || end <= start) {
            return new ExtractionResult.NeedsReview(
                    "Extraction did not produce parseable JSON.",
                    List.of("Model response contained no JSON object"),
                    metadata);
        }

        try {
            JsonNode rawJson = mapper.readTree(responseText.substring(start, end + 1));
            ExtractionEnvelope envelope = mapper.treeToValue(rawJson, ExtractionEnvelope.class);
            return new ExtractionResult.Success(envelope, rawJson, metadata);
        } catch (JacksonException e) {
            return new ExtractionResult.NeedsReview(
                    "Extraction did not produce parseable JSON.",
                    List.of("Model response JSON invalid: " + e.getOriginalMessage()),
                    metadata);
        }
    }
}
