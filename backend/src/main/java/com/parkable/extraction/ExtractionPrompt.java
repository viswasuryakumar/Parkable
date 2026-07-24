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
                - If the sign states ANY hours of enforcement (e.g. "8AM-5PM", "8:30 TO 5:30"), \
                you MUST populate that same rule's time_windows with a matching start_time/ \
                end_time - never leave time_windows empty while the hours appear only in \
                raw_text or description. Empty time_windows means "enforced at ALL hours, \
                every hour of the day" - using it for a sign that actually names specific \
                hours is exactly the kind of confidently-wrong answer this system must never \
                give. Leave time_windows empty ONLY when the sign truly names no hours at all.
                - "No return within X minutes/hours" (and similar re-parking clauses) is a \
                CONDITION of the time-limit rule it accompanies - put it in that rule's \
                restriction.extra_details, never emit it as a separate no_parking rule. \
                A sign saying "20 mins, no return within 40 mins" ALLOWS parking for 20 \
                minutes.
                - "No Double Parking" (or similar wording that only prohibits standing a \
                SECOND row out from the curb) is NOT the same as "No Parking" - it does not \
                restrict a single vehicle legally parked at the curb itself. Use type \
                "double_parking_prohibited" for it, never "no_parking" or "restricted" - \
                misclassifying it makes an unrelated regulation block an otherwise-legal spot.
                - Do NOT guess fields you cannot read. Never decide whether parking is \
                allowed - only transcribe the rules.
                """.formatted(schemaJson, parserVersion, now);
    }
}
