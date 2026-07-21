package com.parkable.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

/**
 * Extraction provider via OpenRouter (openrouter.ai) — a single API that
 * proxies many LLM vendors, letting a cost-sensitive deployment point at
 * whichever vision-capable model is cheapest without touching the engine
 * (Strategy pattern: this is just another {@link VisionExtractor}).
 *
 * <p>No official OpenRouter Java SDK exists, so this talks its
 * OpenAI-compatible REST API directly via {@link HttpClient} — the same
 * approach the Phase 1 plan originally sketched for Claude before an
 * official Anthropic SDK was adopted.
 *
 * <p>Requires {@code OPENROUTER_API_KEY}. The model is overridable via
 * {@code OPENROUTER_MODEL} (default {@link #DEFAULT_MODEL}) because
 * OpenRouter's catalog and pricing change often — "the current cheapest
 * vision model" is a config value, not a compile-time constant. Check
 * https://openrouter.ai/models (filter: supports image input) before
 * relying on the default.
 */
public final class OpenRouterVisionExtractor implements VisionExtractor {

    public static final String PARSER_VERSION = "openrouter-vision-extractor-v1";

    static final String DEFAULT_MODEL = "openai/gpt-4o-mini";

    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");

    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String schemaJson;

    public OpenRouterVisionExtractor(String apiKey) {
        this(apiKey, System.getenv("OPENROUTER_MODEL"), HttpClient.newHttpClient(), Clock.systemUTC());
    }

    OpenRouterVisionExtractor(String apiKey, String modelOverride, HttpClient httpClient, Clock clock) {
        this.apiKey = requireNonBlank(apiKey, "apiKey");
        this.model = (modelOverride == null || modelOverride.isBlank()) ? DEFAULT_MODEL : modelOverride;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.schemaJson = ExtractionSchema.loadJson();
    }

    @Override
    public ExtractionResult extract(ImageInput image) {
        String responseText;
        try {
            responseText = callOpenRouter(image);
        } catch (IOException e) {
            throw new VisionExtractionException("OpenRouter API call failed for " + image.sourceReference(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VisionExtractionException("OpenRouter API call interrupted for " + image.sourceReference(), e);
        }
        return parseResponse(responseText, image);
    }

    private String callOpenRouter(ImageInput image) throws IOException, InterruptedException {
        String dataUrl = "data:" + image.mediaType() + ";base64,"
                + Base64.getEncoder().encodeToString(image.bytes());

        ObjectNode imageContent = mapper.createObjectNode();
        imageContent.put("type", "image_url");
        imageContent.putObject("image_url").put("url", dataUrl);

        ObjectNode textContent = mapper.createObjectNode();
        textContent.put("type", "text");
        textContent.put("text", ExtractionPrompt.build(schemaJson, PARSER_VERSION, clock.instant()));

        ArrayNode content = mapper.createArrayNode();
        content.add(imageContent);
        content.add(textContent);

        ObjectNode userMessage = mapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.set("content", content);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("messages", mapper.createArrayNode().add(userMessage));

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("OpenRouter returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("OpenRouter response had no choices: " + response.body());
        }
        return choices.get(0).path("message").path("content").asText();
    }

    /** Package-private for offline unit tests — mirrors ClaudeVisionExtractor's testing seam. */
    ExtractionResult parseResponse(String responseText, ImageInput image) {
        ExtractionMetadata metadata = new ExtractionMetadata(
                image.sourceReference().toString(), PARSER_VERSION, clock.instant());
        return ExtractionResponseParser.parse(responseText, mapper, metadata);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
