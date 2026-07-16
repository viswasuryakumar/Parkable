package com.parkable.extraction;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.extraction.dto.ExtractionEnvelope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Offline extraction stub: instead of calling an LLM, looks up a pre-recorded
 * JSON fixture next to the image ({@code sign.jpg} → {@code sign.json}).
 * Keeps the CLI, engine, and integration tests runnable with no API key.
 */
public final class FixtureVisionExtractor implements VisionExtractor {

    public static final String PARSER_VERSION = "fixture-vision-extractor-v1";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public ExtractionResult extract(ImageInput image) {
        Path fixturePath = fixturePathFor(image.sourceReference());
        String json;
        try {
            json = Files.readString(fixturePath);
        } catch (IOException e) {
            // A missing/unreadable fixture is a setup error, not an unreadable
            // sign — surface it loudly instead of prompting a photo retake.
            throw new VisionExtractionException(
                    "Fixture not readable: " + fixturePath + " (expected next to " + image.sourceReference() + ")", e);
        }

        JsonNode rawJson;
        ExtractionEnvelope envelope;
        try {
            rawJson = mapper.readTree(json);
            envelope = mapper.treeToValue(rawJson, ExtractionEnvelope.class);
        } catch (JacksonException e) {
            // Unparseable content is the fixture equivalent of an LLM
            // returning non-JSON: an expected-invalid outcome for the retry
            // path, not an exception.
            return new ExtractionResult.NeedsReview(
                    "Extraction did not produce parseable JSON.",
                    List.of("Fixture " + fixturePath.getFileName() + ": " + e.getOriginalMessage()),
                    metadataFor(image, null));
        }

        return new ExtractionResult.Success(envelope, rawJson, metadataFor(image, envelope));
    }

    private static Path fixturePathFor(Path imagePath) {
        String fileName = imagePath.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        String baseName = lastDot == -1 ? fileName : fileName.substring(0, lastDot);
        return imagePath.resolveSibling(baseName + ".json");
    }

    private ExtractionMetadata metadataFor(ImageInput image, ExtractionEnvelope envelope) {
        // Fixtures are canned data: their own ingestion_timestamp is the
        // honest extraction time, keeping this extractor fully deterministic
        // (no wall clock). EPOCH marks a fixture that didn't carry one.
        Instant extractedAt = Instant.EPOCH;
        if (envelope != null && envelope.ingestionTimestamp() != null) {
            try {
                extractedAt = Instant.parse(envelope.ingestionTimestamp());
            } catch (DateTimeParseException ignored) {
                // semantic validation reports this; metadata keeps the sentinel
            }
        }
        return new ExtractionMetadata(image.sourceReference().toString(), PARSER_VERSION, extractedAt);
    }
}
