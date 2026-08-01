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

    // Tighter than CheckHandler.CHECK_RADIUS_METERS (25m) on purpose - see
    // the comment at the supersedeNearby call site.
    static final double SUPERSEDE_RADIUS_METERS = 8.0;

    private final ObjectMapper mapper = new ObjectMapper();
    private final VisionExtractor extractor;
    private final RuleRepository repository;
    private final RulesEngine engine;
    private final Clock clock;
    private final PhotoUploader photoUploader;

    /** No-arg constructor AWS Lambda actually invokes in production; wires from env vars (D4). */
    public ScanHandler() {
        this(ExtractorFactory.extractor(EnvConfig.fromEnvironment()),
                StorageStack.from(EnvConfig.fromEnvironment()).repository(),
                Clock.systemUTC(),
                S3PhotoUploader.fromEnvironment());
    }

    public ScanHandler(VisionExtractor delegate, RuleRepository repository, Clock clock) {
        this(delegate, repository, clock, (bytes, mediaType, key) -> Optional.empty());
    }

    public ScanHandler(VisionExtractor delegate, RuleRepository repository, Clock clock, PhotoUploader photoUploader) {
        this.extractor = new ValidatingRetryingVisionExtractor(
                Objects.requireNonNull(delegate, "delegate"),
                new RuleJsonSchemaValidator(),
                new SemanticRuleValidator());
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.photoUploader = Objects.requireNonNull(photoUploader, "photoUploader");
        this.engine = new RulesEngine(new TemporalRuleEvaluator(new UsFederalHolidayCalendar()));
    }

    /**
     * Uploads a scan photo and returns a way to view it, or empty if photo
     * storage isn't configured/fails - a thumbnail is a nice-to-have, never
     * something that should fail an otherwise-successful scan.
     */
    interface PhotoUploader {
        Optional<String> upload(byte[] photoBytes, String mediaType, String key);
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
            List<Rule> rules = RuleFactory.fromEnvelope(success.envelope());

            // A rescan of the same physical sign must REPLACE the previous
            // camera_scan reading there, not accumulate alongside it -
            // otherwise /nearby and /check end up reconciling multiple,
            // possibly-contradictory extractions of what is really one sign.
            // But proximity alone can't tell "same sign, re-read" apart from
            // "different sign, nearby": distinct regulatory signs commonly
            // stand within a few metres of each other (a loading zone ending
            // where a permit zone begins), well inside realistic GPS
            // accuracy, and an earlier proximity-only version of this wiped
            // out a real, different 3-panel sign the moment a second sign
            // was scanned nearby. supersedeMatching only deletes existing
            // rows that describesSameRegulation() one of THIS scan's rules -
            // same day pattern, same time windows, same type-specific detail
            // - so a genuinely different sign a few metres away survives.
            repository.supersedeMatching(
                    "camera_scan", request.latitude(), request.longitude(), SUPERSEDE_RADIUS_METERS, rules);
            repository.save(new ExtractionRecord(
                    success.envelope(),
                    success.metadata().photoReference(),
                    "camera_scan",
                    success.metadata().parserVersion(),
                    Optional.of(new ExtractionRecord.GpsCoordinates(request.latitude(), request.longitude())),
                    success.metadata().extractedAt()));

            Optional<String> photoUrl = uploadPhotoSafely(request, success);
            Optional<Double> confidence = Optional.ofNullable(success.envelope().confidence());

            VerdictResult verdict = engine.evaluate(rules, request.at(), request.zone(), request.side());
            List<StoredRule> stored = rules.stream()
                    .map(rule -> new StoredRule(rule, "camera_scan", success.metadata().parserVersion(),
                            request.latitude(), request.longitude(), success.envelope().extractionId(),
                            success.metadata().photoReference()))
                    .toList();
            return Responses.verdict(verdict, stored, photoUrl, confidence);
        } catch (QueryParams.BadRequestException e) {
            return Responses.badRequest(e.getMessage());
        } catch (RuntimeException e) {
            // Anything below input validation (vision provider quota/auth/5xx,
            // any other unexpected extractor failure) must still return a
            // real response through Responses.json() - an uncaught exception
            // here skips Lambda's proxy-integration response entirely,
            // including the CORS header every other response carries, which
            // the browser then reports as an opaque "Failed to fetch" rather
            // than a real error. Found live: OpenRouter ran out of credits,
            // every scan failed this way with no usable message reaching the
            // client. Logged here (stderr -> CloudWatch) since the client
            // only ever sees the generic message, never e's own detail.
            System.err.println("Scan failed: " + e);
            return Responses.serviceUnavailable("Could not read the sign right now. Please try again in a moment.");
        }
    }

    /**
     * A thumbnail is a nice-to-have; a broken or misbehaving uploader (one
     * that violates {@link PhotoUploader}'s documented contract and throws
     * instead of returning empty) must still never fail an otherwise-
     * successful scan - enforced here rather than trusted to every
     * implementation.
     */
    private Optional<String> uploadPhotoSafely(ScanRequest request, ExtractionResult.Success success) {
        try {
            return photoUploader.upload(
                    request.image().bytes(), request.image().mediaType(), success.metadata().photoReference());
        } catch (RuntimeException e) {
            return Optional.empty();
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
