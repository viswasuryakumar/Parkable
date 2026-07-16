package com.parkable.extraction.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Jackson DTO mirroring docs/schema.md's ExtractionEnvelope — what an
 * ingestion pipeline (gov ETL or LLM extraction) produces.
 *
 * <p>Deliberately lenient: every field nullable, unknown fields ignored.
 * Correctness is enforced by schema + semantic validation, not by this DTO —
 * a strict DTO would turn expected-invalid extractions (which must flow into
 * the retry path) into mapping exceptions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractionEnvelope(
        @JsonProperty("extraction_id") String extractionId,
        @JsonProperty("source") String source,
        @JsonProperty("city") String city,
        @JsonProperty("state") String state,
        @JsonProperty("parser_version") String parserVersion,
        @JsonProperty("ingestion_timestamp") String ingestionTimestamp,
        @JsonProperty("extraction_method") String extractionMethod,
        @JsonProperty("confidence") Double confidence,
        @JsonProperty("coverage_completeness") Double coverageCompleteness,
        @JsonProperty("notes") String notes,
        @JsonProperty("raw_text") String rawText,
        @JsonProperty("rules") List<RuleDto> rules
) {}
