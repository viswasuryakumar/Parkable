package com.parkable.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkable.extraction.dto.ExtractionEnvelope;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of one extraction. A sealed result type, not an exception:
 * {@link NeedsReview} ("couldn't read the sign clearly") is a first-class
 * expected outcome, while {@link VisionExtractionException} stays reserved for
 * genuinely exceptional failures (network I/O, missing fixture).
 */
public sealed interface ExtractionResult {

    ExtractionMetadata metadata();

    /**
     * Extraction produced a parseable envelope. Carries both the mapped DTO
     * and the raw JSON because validation is layered: the schema validator
     * needs the raw tree (a type mismatch must be a validation error, not a
     * mapping crash), the semantic validator the DTO.
     */
    record Success(ExtractionEnvelope envelope, JsonNode rawJson, ExtractionMetadata metadata)
            implements ExtractionResult {
        public Success {
            Objects.requireNonNull(envelope, "envelope");
            Objects.requireNonNull(rawJson, "rawJson");
            Objects.requireNonNull(metadata, "metadata");
        }
    }

    /**
     * Extraction could not produce trustworthy rules. {@code message} is the
     * honest user-facing text; {@code details} carries the specific validation
     * errors for trace/logs (never shown raw to an end user).
     */
    record NeedsReview(String message, List<String> details, ExtractionMetadata metadata)
            implements ExtractionResult {
        public NeedsReview {
            Objects.requireNonNull(message, "message");
            details = List.copyOf(details);
            Objects.requireNonNull(metadata, "metadata");
        }
    }
}
