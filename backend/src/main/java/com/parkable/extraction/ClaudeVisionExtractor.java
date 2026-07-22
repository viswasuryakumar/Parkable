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
import com.fasterxml.jackson.databind.ObjectMapper;

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

    // v2: shared prompt gained the "no return within X is a time-limit
    // condition, not its own no_parking rule" instruction (2026-07-22, found
    // via a real user scan yielding a confidently wrong NOT_PARKABLE).
    public static final String PARSER_VERSION = "claude-vision-extractor-v2";

    // Single named constant so a model upgrade is a one-line change.
    static final String CLAUDE_VISION_MODEL = "claude-opus-4-8";

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
        this.schemaJson = ExtractionSchema.loadJson();
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
        return ExtractionResponseParser.parse(responseText, mapper, metadata);
    }

    private String buildPrompt() {
        return ExtractionPrompt.build(schemaJson, PARSER_VERSION, clock.instant());
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
}
