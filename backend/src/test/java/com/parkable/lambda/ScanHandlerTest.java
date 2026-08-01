package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.extraction.ExtractionMetadata;
import com.parkable.extraction.ExtractionResult;
import com.parkable.extraction.VisionExtractor;
import com.parkable.extraction.dto.ExtractionEnvelope;
import com.parkable.repository.InMemoryRuleRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /scan tests over a fake extractor (VisionExtractor is a single-method
 * interface, so fakes are lambdas). The valid envelope is the golden
 * fixture, so the full validate → factory → engine path is real.
 */
class ScanHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant SCAN_TIME = Instant.parse("2026-07-14T18:04:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(SCAN_TIME, ZoneOffset.UTC);
    private static final ExtractionMetadata METADATA =
            new ExtractionMetadata("s3://bucket/scan-1.jpg", "test-parser-v1", SCAN_TIME);

    private static ExtractionResult.Success validSuccess() {
        try {
            JsonNode rawJson = MAPPER.readTree(
                    Files.readString(Path.of("src/test/resources/fixtures/sign.json")));
            return new ExtractionResult.Success(
                    MAPPER.treeToValue(rawJson, ExtractionEnvelope.class), rawJson, METADATA);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String validBody() {
        return """
                {"photo_base64":"%s","media_type":"image/jpeg","lat":37.7749,"lng":-122.4194,
                 "at":"2026-07-14T18:04:00Z"}
                """.formatted(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
    }

    @Test
    void validScanReturnsVerdictAndPersistsProvenance() throws Exception {
        InMemoryRuleRepository repository = new InMemoryRuleRepository();
        ScanHandler handler = new ScanHandler(image -> validSuccess(), repository, FIXED_CLOCK);

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                new APIGatewayProxyRequestEvent().withBody(validBody()), null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        JsonNode json = MAPPER.readTree(response.getBody());
        assertThat(json.get("verdict").asText()).isEqualTo("NOT_PARKABLE");
        assertThat(json.get("source").asText()).isEqualTo("camera_scan");
        // The fixture's own confidence (0.97) - always available at scan
        // time, unlike /check's later re-reads of already-stored rules.
        assertThat(json.get("confidence").asDouble()).isEqualTo(0.97);
        // No PhotoUploader wired (3-arg constructor default) - a missing
        // thumbnail must never fail the scan itself.
        assertThat(json.get("photo_url").isNull()).isTrue();

        // Reproducibility contract: envelope + GPS + parser version stored.
        assertThat(repository.findAll()).hasSize(1);
        var record = repository.findAll().getFirst();
        assertThat(record.parserVersion()).isEqualTo("test-parser-v1");
        assertThat(record.gpsLocation()).isPresent();
        assertThat(record.gpsLocation().get().latitude()).isEqualTo(37.7749);
    }

    @Test
    void rescanningTheSameSpotSupersedesThePreviousScanInsteadOfAccumulating() throws Exception {
        // The real bug this guards: a rescan of the same physical sign must
        // replace the stale reading, not coexist with it - otherwise /nearby
        // ends up serving two different (and possibly contradictory)
        // extractions of what is really one sign.
        InMemoryRuleRepository repository = new InMemoryRuleRepository();
        ScanHandler handler = new ScanHandler(image -> validSuccess(), repository, FIXED_CLOCK);

        handler.handleRequest(new APIGatewayProxyRequestEvent().withBody(validBody()), null);
        handler.handleRequest(new APIGatewayProxyRequestEvent().withBody(validBody()), null);

        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void aDifferentSignFifteenMetersAwayIsNotWipedOutByRescanningTheFirst() throws Exception {
        // The supersede radius must be tighter than CheckHandler's 25m query
        // radius: two genuinely different signs commonly stand within 25m of
        // each other on a real block, and someone rescanning sign A must
        // never delete a stranger's earlier scan of sign B nearby. 15m is
        // inside the old (wrong) 25m radius but outside the corrected 8m one.
        InMemoryRuleRepository repository = new InMemoryRuleRepository();
        VisionExtractor secondScanDifferentId = image -> withExtractionId(validSuccess(), "scan-2");
        // ~15.6m north of validBody()'s (37.7749, -122.4194).
        String nearbyDifferentSignBody = """
                {"photo_base64":"%s","media_type":"image/jpeg","lat":37.77504,"lng":-122.4194,
                 "at":"2026-07-14T18:04:00Z"}
                """.formatted(java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));

        new ScanHandler(image -> validSuccess(), repository, FIXED_CLOCK)
                .handleRequest(new APIGatewayProxyRequestEvent().withBody(validBody()), null);
        new ScanHandler(secondScanDifferentId, repository, FIXED_CLOCK)
                .handleRequest(new APIGatewayProxyRequestEvent().withBody(nearbyDifferentSignBody), null);

        assertThat(repository.findAll()).hasSize(2);
    }

    @Test
    void rescanFarAwayDoesNotSupersedeADifferentSign() throws Exception {
        // Two genuinely distinct scan events need distinct extraction_ids -
        // a real vision call always mints a fresh one; only reusing the
        // fixture's fixed id (as validSuccess() does every time) would
        // collide at InMemoryRuleRepository's own extraction_id key,
        // independent of location entirely.
        InMemoryRuleRepository repository = new InMemoryRuleRepository();
        VisionExtractor secondScanDifferentId = image -> withExtractionId(validSuccess(), "scan-2");
        ScanHandler handler = new ScanHandler(secondScanDifferentId, repository, FIXED_CLOCK);
        String farAwayBody = """
                {"photo_base64":"%s","media_type":"image/jpeg","lat":40.7128,"lng":-74.0060,
                 "at":"2026-07-14T18:04:00Z"}
                """.formatted(java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));

        new ScanHandler(image -> validSuccess(), repository, FIXED_CLOCK)
                .handleRequest(new APIGatewayProxyRequestEvent().withBody(validBody()), null);
        handler.handleRequest(new APIGatewayProxyRequestEvent().withBody(farAwayBody), null);

        assertThat(repository.findAll()).hasSize(2);
    }

    private static ExtractionResult.Success withExtractionId(ExtractionResult.Success original, String newId) {
        ExtractionEnvelope e = original.envelope();
        ExtractionEnvelope withId = new ExtractionEnvelope(newId, e.source(), e.city(), e.state(),
                e.parserVersion(), e.ingestionTimestamp(), e.extractionMethod(), e.confidence(),
                e.coverageCompleteness(), e.notes(), e.rawText(), e.rules());
        return new ExtractionResult.Success(withId, original.rawJson(), original.metadata());
    }

    @Test
    void photoUrlFromAnInjectedUploaderSurfacesInTheResponse() throws Exception {
        InMemoryRuleRepository repository = new InMemoryRuleRepository();
        ScanHandler.PhotoUploader fakeUploader =
                (bytes, mediaType, key) -> java.util.Optional.of("https://photos.example/" + key);
        ScanHandler handler = new ScanHandler(image -> validSuccess(), repository, FIXED_CLOCK, fakeUploader);

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                new APIGatewayProxyRequestEvent().withBody(validBody()), null);

        JsonNode json = MAPPER.readTree(response.getBody());
        assertThat(json.get("photo_url").asText()).startsWith("https://photos.example/");
    }

    @Test
    void aFailingPhotoUploadNeverFailsTheScanItself() throws Exception {
        // A thumbnail is a nice-to-have; a misbehaving uploader that
        // violates PhotoUploader's contract (throws instead of returning
        // empty) must still never take down an otherwise-successful scan.
        InMemoryRuleRepository repository = new InMemoryRuleRepository();
        ScanHandler.PhotoUploader throwingUploader = (bytes, mediaType, key) -> {
            throw new RuntimeException("S3 is down");
        };
        ScanHandler handler =
                new ScanHandler(image -> validSuccess(), repository, FIXED_CLOCK, throwingUploader);

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                new APIGatewayProxyRequestEvent().withBody(validBody()), null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        JsonNode json = MAPPER.readTree(response.getBody());
        assertThat(json.get("verdict").asText()).isEqualTo("NOT_PARKABLE");
        assertThat(json.get("photo_url").isNull()).isTrue();
    }

    @Test
    void unreadableSignReturns422AndStoresNothing() {
        InMemoryRuleRepository repository = new InMemoryRuleRepository();
        VisionExtractor alwaysNeedsReview = image -> new ExtractionResult.NeedsReview(
                "Couldn't read this sign clearly.", List.of("blurry"), METADATA);
        ScanHandler handler = new ScanHandler(alwaysNeedsReview, repository, FIXED_CLOCK);

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                new APIGatewayProxyRequestEvent().withBody(validBody()), null);

        assertThat(response.getStatusCode()).isEqualTo(422);
        assertThat(response.getBody()).contains("NEEDS_REVIEW");
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void extractorFailureReturns503WithCorsHeaderInsteadOfAnUncaughtException() {
        // The real bug this guards: OpenRouter returning a 402 (quota
        // exhausted) - or any other extractor RuntimeException - used to
        // propagate uncaught out of handleRequest, skipping Responses.json()
        // entirely and, with it, the CORS header every other response
        // carries. The browser then reported this as an opaque "Failed to
        // fetch" with zero usable information, on every single scan,
        // regardless of network quality.
        InMemoryRuleRepository repository = new InMemoryRuleRepository();
        VisionExtractor alwaysFails = image -> {
            throw new RuntimeException("OpenRouter returned HTTP 402: Insufficient credits");
        };
        ScanHandler handler = new ScanHandler(alwaysFails, repository, FIXED_CLOCK);

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                new APIGatewayProxyRequestEvent().withBody(validBody()), null);

        assertThat(response.getStatusCode()).isEqualTo(503);
        assertThat(response.getHeaders()).containsEntry("Access-Control-Allow-Origin", "*");
        assertThat(response.getBody()).contains("SERVICE_UNAVAILABLE");
        // The raw upstream error (which may include provider account
        // details) must never reach the client - only the generic message.
        assertThat(response.getBody()).doesNotContain("OpenRouter", "402", "credits");
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void badBodiesReturn400() {
        ScanHandler handler = new ScanHandler(image -> validSuccess(), new InMemoryRuleRepository(), FIXED_CLOCK);

        assertThat(handler.handleRequest(new APIGatewayProxyRequestEvent(), null)
                .getStatusCode()).isEqualTo(400);                                  // no body
        assertThat(handler.handleRequest(new APIGatewayProxyRequestEvent().withBody("not json"), null)
                .getStatusCode()).isEqualTo(400);
        assertThat(handler.handleRequest(new APIGatewayProxyRequestEvent().withBody(
                "{\"photo_base64\":\"AQID\",\"media_type\":\"image/bmp\",\"lat\":1,\"lng\":1}"), null)
                .getStatusCode()).isEqualTo(400);                                  // unsupported type
        assertThat(handler.handleRequest(new APIGatewayProxyRequestEvent().withBody(
                "{\"photo_base64\":\"@@@\",\"media_type\":\"image/jpeg\",\"lat\":1,\"lng\":1}"), null)
                .getStatusCode()).isEqualTo(400);                                  // bad base64
        assertThat(handler.handleRequest(new APIGatewayProxyRequestEvent().withBody(
                "{\"photo_base64\":\"AQID\",\"media_type\":\"image/jpeg\",\"lat\":95,\"lng\":1}"), null)
                .getStatusCode()).isEqualTo(400);                                  // lat out of range
    }
}
