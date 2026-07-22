package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.calendar.UsFederalHolidayCalendar;
import com.parkable.engine.RulesEngine;
import com.parkable.engine.TemporalRuleEvaluator;
import com.parkable.extraction.ExtractionResult;
import com.parkable.extraction.ImageInput;
import com.parkable.extraction.ValidatingRetryingVisionExtractor;
import com.parkable.extraction.VisionExtractor;
import com.parkable.factory.RuleFactory;
import com.parkable.lambda.config.EnvConfig;
import com.parkable.lambda.config.ExtractorFactory;
import com.parkable.lambda.config.StorageStack;
import com.parkable.lambda.port.StoredRule;
import com.parkable.model.Rule;
import com.parkable.model.Side;
import com.parkable.model.VerdictResult;
import com.parkable.repository.ExtractionRecord;
import com.parkable.repository.RuleRepository;
import com.parkable.validation.RuleJsonSchemaValidator;
import com.parkable.validation.SemanticRuleValidator;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * POST /scan — fresh extraction: photo (base64, plan decision D3) →
 * validated extraction → persisted with provenance → verdict. The same
 * pipeline as ScanCLI, wearing an HTTP costume; still zero business logic.
 */
public class ScanHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Set<String> SUPPORTED_MEDIA_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private final ObjectMapper mapper = new ObjectMapper();
    private final VisionExtractor extractor;
    private final RuleRepository repository;
    private final RulesEngine engine;
    private final Clock clock;

    /** No-arg constructor AWS Lambda actually invokes in production; wires from env vars (D4). */
    public ScanHandler() {
        this(ExtractorFactory.extractor(EnvConfig.fromEnvironment()),
                StorageStack.from(EnvConfig.fromEnvironment()).repository(),
                Clock.systemUTC());
    }

    public ScanHandler(VisionExtractor delegate, RuleRepository repository, Clock clock) {
        this.extractor = new ValidatingRetryingVisionExtractor(
                Objects.requireNonNull(delegate, "delegate"),
                new RuleJsonSchemaValidator(),
                new SemanticRuleValidator());
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.engine = new RulesEngine(new TemporalRuleEvaluator(new UsFederalHolidayCalendar()));
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            ScanRequest request = parseBody(event.getBody());

            ExtractionResult result = extractor.extract(request.image());
            if (result instanceof ExtractionResult.NeedsReview review) {
                // Honest uncertainty over a confidently wrong verdict.
                return Responses.needsReview(review.message());
            }

            ExtractionResult.Success success = (ExtractionResult.Success) result;
            repository.save(new ExtractionRecord(
                    success.envelope(),
                    success.metadata().photoReference(),
                    "camera_scan",
                    success.metadata().parserVersion(),
                    Optional.of(new ExtractionRecord.GpsCoordinates(request.latitude(), request.longitude())),
                    success.metadata().extractedAt()));

            List<Rule> rules = RuleFactory.fromEnvelope(success.envelope());
            VerdictResult verdict = engine.evaluate(rules, request.at(), request.zone(), request.side());
            List<StoredRule> stored = rules.stream()
                    .map(rule -> new StoredRule(rule, "camera_scan", success.metadata().parserVersion(),
                            request.latitude(), request.longitude()))
                    .toList();
            return Responses.verdict(verdict, stored);
        } catch (QueryParams.BadRequestException e) {
            return Responses.badRequest(e.getMessage());
        }
    }

    private record ScanRequest(
            ImageInput image, double latitude, double longitude,
            Instant at, ZoneId zone, Optional<Side> side) {}

    private ScanRequest parseBody(String body) {
        if (body == null || body.isBlank()) {
            throw new QueryParams.BadRequestException("Request body is required");
        }
        JsonNode json;
        try {
            json = mapper.readTree(body);
        } catch (JacksonException e) {
            throw new QueryParams.BadRequestException("Request body is not valid JSON");
        }

        String photoBase64 = textField(json, "photo_base64");
        String mediaType = textField(json, "media_type");
        if (!SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
            throw new QueryParams.BadRequestException("media_type must be one of " + SUPPORTED_MEDIA_TYPES);
        }
        byte[] photoBytes;
        try {
            photoBytes = Base64.getDecoder().decode(photoBase64);
        } catch (IllegalArgumentException e) {
            throw new QueryParams.BadRequestException("photo_base64 is not valid base64");
        }

        double latitude = numberField(json, "lat");
        double longitude = numberField(json, "lng");
        QueryParams.requireValidCoordinates(latitude, longitude);

        Instant at = QueryParams.optionalInstant(text(json, "at"), "at").orElseGet(clock::instant);
        ZoneId zone = QueryParams.zoneOrDefault(text(json, "zone"));
        Optional<Side> side = QueryParams.optionalSide(text(json, "side"));

        // Phase 2.1 moves photos to S3; this synthetic reference keeps the
        // provenance chain intact until then.
        ImageInput image = new ImageInput(photoBytes, mediaType, Path.of("scan", UUID.randomUUID().toString()));
        return new ScanRequest(image, latitude, longitude, at, zone, side);
    }

    private static String textField(JsonNode json, String name) {
        JsonNode node = json.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new QueryParams.BadRequestException("Missing required string field: " + name);
        }
        return node.asText();
    }

    private static double numberField(JsonNode json, String name) {
        JsonNode node = json.get(name);
        if (node == null || !node.isNumber()) {
            throw new QueryParams.BadRequestException("Missing required numeric field: " + name);
        }
        return node.asDouble();
    }

    private static String text(JsonNode json, String name) {
        JsonNode node = json.get(name);
        return node == null || node.isNull() ? null : node.asText();
    }
}
