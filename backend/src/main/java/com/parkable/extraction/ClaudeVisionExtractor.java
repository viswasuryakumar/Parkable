package com.parkable.extraction;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.extraction.dto.ExtractionEnvelope;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Real extraction provider: sends the sign photo to the Anthropic Messages
 * API and parses the returned JSON envelope. Adapter over the Anthropic SDK —
 * swapping providers means adding another {@link VisionExtractor}, zero
 * engine changes.
 *
 * <p>The model NEVER decides a verdict; it only transcribes the sign into the
 * docs/schema.md envelope, which then flows through the same validation and
 * rules-engine path as every other source.
 *
 * <p>Requires {@code ANTHROPIC_API_KEY} in the environment (resolved by the
 * SDK); the CLI defaults to the offline fixture extractor so the jar runs
 * without a key.
 */
public final class ClaudeVisionExtractor implements VisionExtractor {

    public static final String PARSER_VERSION = "claude-vision-extractor-v1";

    // Single named constant so a model upgrade is a one-line change.
    static final String CLAUDE_VISION_MODEL = "claude-opus-4-8";

    private static final String SCHEMA_RESOURCE = "/schema/parking-rule-schema.json";

    private final AnthropicClient client;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String schemaJson;

    public ClaudeVisionExtractor() {
        this(AnthropicOkHttpClient.fromEnv(), Clock.systemUTC());
    }

    ClaudeVisionExtractor(AnthropicClient client, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.schemaJson = loadSchemaJson();
    }

    @Override
    public ExtractionResult extract(ImageInput image) {
        String responseText;
        try {
            responseText = callClaude(image);
        } catch (AnthropicException e) {
            // Transport/API failures are genuinely exceptional; an unreadable
            // sign would come back as prose/invalid JSON and flow into the
            // NeedsReview path below instead.
            throw new VisionExtractionException(
                    "Anthropic API call failed for " + image.sourceReference(), e);
        }
        return parseResponse(responseText, image);
    }

    private String callClaude(ImageInput image) {
        ImageBlockParam imageBlock = ImageBlockParam.builder()
                .source(Base64ImageSource.builder()
                        .mediaType(mediaTypeOf(image.mediaType()))
                        .data(Base64.getEncoder().encodeToString(image.bytes()))
                        .build())
                .build();
        TextBlockParam promptBlock = TextBlockParam.builder()
                .text(buildPrompt())
                .build();

        MessageCreateParams params = MessageCreateParams.builder()
                .model(CLAUDE_VISION_MODEL)
                .maxTokens(16000L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofImage(imageBlock),
                        ContentBlockParam.ofText(promptBlock)))
                .build();

        Message response = client.messages().create(params);
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .collect(Collectors.joining());
    }

    /**
     * Parses the model's response text into an extraction result. Non-JSON or
     * shape-mismatched output is an EXPECTED failure mode (feeds the retry
     * decorator), never an exception. Package-private for offline unit tests.
     */
    ExtractionResult parseResponse(String responseText, ImageInput image) {
        ExtractionMetadata metadata = new ExtractionMetadata(
                image.sourceReference().toString(), PARSER_VERSION, clock.instant());

        // Models occasionally wrap JSON in prose or markdown fences despite
        // instructions; extracting the outermost object tolerates that
        // without accepting arbitrary garbage (the schema validator still
        // gets the final say).
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

    private String buildPrompt() {
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
                """.formatted(schemaJson, PARSER_VERSION, clock.instant());
    }

    private static Base64ImageSource.MediaType mediaTypeOf(String mediaType) {
        return switch (mediaType) {
            case "image/jpeg" -> Base64ImageSource.MediaType.IMAGE_JPEG;
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> throw new VisionExtractionException("Unsupported image media type: " + mediaType);
        };
    }

    private static String loadSchemaJson() {
        try (InputStream in = ClaudeVisionExtractor.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Schema resource missing from classpath: " + SCHEMA_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read schema resource " + SCHEMA_RESOURCE, e);
        }
    }
}
