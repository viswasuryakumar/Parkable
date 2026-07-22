package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.lambda.port.StoredRule;
import com.parkable.model.VerdictResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON response shaping for the API contract in
 * docs/plans/phase2-aws-backend.md §3. Pure presentation — verdicts arrive
 * fully formed from the engine.
 */
final class Responses {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Access-Control-Allow-Origin lets the Expo WEB build (a browser page on
    // a different origin) call this API; native apps ignore it. Preflight
    // OPTIONS is handled by API Gateway (SAM Cors config), but the actual
    // responses must carry the header themselves.
    private static final Map<String, String> JSON_HEADERS = Map.of(
            "Content-Type", "application/json",
            "Access-Control-Allow-Origin", "*");

    private Responses() {}

    static APIGatewayProxyResponseEvent verdict(VerdictResult result, List<StoredRule> stored) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("verdict", result.verdict().name());
        body.put("reason", result.triggeringRule().map(m -> m.reason()).orElse(null));
        body.put("rule_id", result.triggeringRule().map(m -> m.rule().metadata().ruleId()).orElse(null));
        body.put("valid_until", result.validUntil().map(Instant::toString).orElse(null));
        body.put("source", sourceOf(result, stored));
        body.put("trace", result.trace());
        return json(200, body);
    }

    static APIGatewayProxyResponseEvent noData() {
        return json(404, Map.of(
                "status", "NO_DATA",
                "message", "No rule data within 25m. Scan the sign."));
    }

    static APIGatewayProxyResponseEvent badRequest(String message) {
        return json(400, Map.of("status", "BAD_REQUEST", "message", message));
    }

    static APIGatewayProxyResponseEvent needsReview(String message) {
        return json(422, Map.of("status", "NEEDS_REVIEW", "message", message));
    }

    static APIGatewayProxyResponseEvent json(int statusCode, Object body) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(statusCode)
                    .withHeaders(JSON_HEADERS)
                    .withBody(MAPPER.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            // Bodies are maps of strings/lists we build ourselves; failure here is a bug.
            throw new IllegalStateException("Failed to serialize response body", e);
        }
    }

    /**
     * The source reported to the user is the triggering rule's provenance;
     * when nothing triggered (all-clear PARKABLE), the first stored rule's
     * source is representative of what answered the question.
     */
    private static String sourceOf(VerdictResult result, List<StoredRule> stored) {
        return result.triggeringRule()
                .flatMap(match -> stored.stream()
                        .filter(s -> s.rule().equals(match.rule()))
                        .findFirst())
                .or(() -> stored.stream().findFirst())
                .map(StoredRule::source)
                .orElse(null);
    }
}
