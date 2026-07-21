package com.parkable.extraction;

import java.time.Instant;

/**
 * Shared instruction prompt for vision-model-based extraction providers
 * (Claude, OpenRouter, ...). One prompt, one place to tune it — every
 * provider gets the same extraction contract.
 */
final class ExtractionPrompt {

    private ExtractionPrompt() {}

    static String build(String schemaJson, String parserVersion, Instant now) {
        return """
                Analyze this parking sign photo. Extract every distinct parking regulation \
                visible on the sign(s).

                Return ONLY a single JSON object (no prose, no markdown fences) that is an \
                ExtractionEnvelope valid against this JSON Schema:

                %s

                Requirements:
                - source: "camera_scan"; extraction_method: "llm"; parser_version: "%s"
                - ingestion_timestamp: "%s"
                - extraction_id: a fresh UUID
                - raw_text: your best-effort transcription of ALL text on the sign
                - confidence: your honest 0-1 estimate; use a LOW value if the sign is \
                blurry, cropped, or partially occluded
                - times in 24h HH:mm; days as MON..SUN; one rule object per distinct regulation
                - Do NOT guess fields you cannot read. Never decide whether parking is \
                allowed - only transcribe the rules.
                """.formatted(schemaJson, parserVersion, now);
    }
}
