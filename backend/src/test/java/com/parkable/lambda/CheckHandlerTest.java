package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.builder.RuleBuilder;
import com.parkable.lambda.port.StoredRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CheckHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-14T18:04:00Z"), ZoneOffset.UTC);

    // Mon-Fri 08:00-18:00 no-parking, like the fixture sign.
    private static final StoredRule NO_PARKING = new StoredRule(
            new RuleBuilder().noParking().withId("cache-rule-1")
                    .onDays(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                    .duringWindow(LocalTime.of(8, 0), LocalTime.of(18, 0))
                    .build(),
            "gov_data", "sfmta-etl-v1", 37.7749, -122.4194, "gov-scan-1", "gov-scan-1");

    private static APIGatewayProxyResponseEvent call(List<StoredRule> stored, Map<String, String> params) {
        CheckHandler handler = new CheckHandler((lat, lng, radius) -> stored, FIXED_CLOCK);
        return handler.handleRequest(new APIGatewayProxyRequestEvent().withQueryStringParameters(params), null);
    }

    private static JsonNode body(APIGatewayProxyResponseEvent response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void insideWindowReturnsNotParkableWithProvenance() {
        APIGatewayProxyResponseEvent response = call(List.of(NO_PARKING), Map.of(
                "lat", "37.7749", "lng", "-122.4194", "at", "2026-07-14T18:04:00Z"));

        assertThat(response.getStatusCode()).isEqualTo(200);
        JsonNode json = body(response);
        assertThat(json.get("verdict").asText()).isEqualTo("NOT_PARKABLE");
        assertThat(json.get("rule_id").asText()).isEqualTo("cache-rule-1");
        assertThat(json.get("source").asText()).isEqualTo("gov_data");
        assertThat(json.get("valid_until").asText()).isEqualTo("2026-07-15T01:00:00Z");
        assertThat(json.get("trace").isArray()).isTrue();
        // gov_data's photoReference is a meaningless reused extraction id
        // (no real photo exists) - must never attempt to resolve one.
        assertThat(json.get("photo_url").isNull()).isTrue();
        assertThat(json.get("confidence").isNull()).isTrue();
    }

    @Test
    void resolvesAPhotoUrlOnlyForTheTriggeringCameraScanRule() {
        StoredRule camera = new StoredRule(
                new RuleBuilder().noParking().withId("cam-1")
                        .onDays(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                        .duringWindow(LocalTime.of(8, 0), LocalTime.of(18, 0))
                        .build(),
                "camera_scan", "test-parser-v1", 37.7749, -122.4194, "scan-1", "scan/photo-1.jpg");
        PhotoUrlResolver fakeResolver = photoReference -> java.util.Optional.of("https://photos.example/" + photoReference);
        CheckHandler handler = new CheckHandler((lat, lng, radius) -> List.of(camera), FIXED_CLOCK, fakeResolver);

        APIGatewayProxyResponseEvent response = handler.handleRequest(new APIGatewayProxyRequestEvent()
                .withQueryStringParameters(Map.of("lat", "37.7749", "lng", "-122.4194", "at", "2026-07-14T18:04:00Z")), null);

        assertThat(body(response).get("photo_url").asText()).isEqualTo("https://photos.example/scan/photo-1.jpg");
    }

    @Test
    void multipleRulesSharingOneScanIdStayAsOneSignsSingleVerdict() {
        // Panels of the SAME photographed sign share one scanId - these
        // must keep evaluating together (e.g. a street-sweeping panel and a
        // time-limit panel on one physical board), not get split into two
        // signs the way two genuinely distinct scans would.
        StoredRule sweepPanel = new StoredRule(
                new RuleBuilder().noParking().withId("panel-sweep")
                        .onDays(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                        .duringWindow(LocalTime.of(8, 0), LocalTime.of(18, 0))
                        .build(),
                "camera_scan", "test-parser-v1", 37.7749, -122.4194, "scan-one-sign", "scan/one.jpg");
        StoredRule limitPanel = new StoredRule(
                new RuleBuilder().permit("A").withId("panel-permit").build(),
                "camera_scan", "test-parser-v1", 37.7749, -122.4194, "scan-one-sign", "scan/one.jpg");

        APIGatewayProxyResponseEvent response = call(List.of(sweepPanel, limitPanel), Map.of(
                "lat", "37.7749", "lng", "-122.4194", "at", "2026-07-14T18:04:00Z"));

        JsonNode json = body(response);
        assertThat(json.has("signs")).isFalse();
        assertThat(json.get("verdict").asText()).isEqualTo("NOT_PARKABLE");
        assertThat(json.get("rule_id").asText()).isEqualTo("panel-sweep");
    }

    @Test
    void distinctSignsWithinRadiusEachGetTheirOwnVerdictInsteadOfBeingBlended() {
        // Two unrelated signs a few metres apart within the 25m check
        // radius: one says no parking right now, the other has no active
        // restriction. A single blended verdict would have to pick one
        // winner and hide the other sign's situation entirely.
        StoredRule signA = new StoredRule(
                new RuleBuilder().noParking().withId("sign-a-rule")
                        .onDays(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                        .duringWindow(LocalTime.of(8, 0), LocalTime.of(18, 0))
                        .build(),
                "camera_scan", "test-parser-v1", 37.7749, -122.4194, "scan-a", "scan/a.jpg");
        StoredRule signB = new StoredRule(
                new RuleBuilder().permit("B").withId("sign-b-rule").build(),
                "camera_scan", "test-parser-v1", 37.77495, -122.41935, "scan-b", "scan/b.jpg");

        APIGatewayProxyResponseEvent response = call(List.of(signA, signB), Map.of(
                "lat", "37.7749", "lng", "-122.4194", "at", "2026-07-14T18:04:00Z"));

        assertThat(response.getStatusCode()).isEqualTo(200);
        JsonNode signs = body(response).get("signs");
        assertThat(signs).isNotNull();
        assertThat(signs).hasSize(2);
        // Closest first: signA is the query point itself (0m), signB a few metres away.
        assertThat(signs.get(0).get("rule_id").asText()).isEqualTo("sign-a-rule");
        assertThat(signs.get(0).get("verdict").asText()).isEqualTo("NOT_PARKABLE");
        assertThat(signs.get(0).get("distance_m").asInt()).isEqualTo(0);
        assertThat(signs.get(1).get("rule_id").asText()).isEqualTo("sign-b-rule");
        assertThat(signs.get(1).get("distance_m").asInt()).isGreaterThan(0);
        assertThat(body(response).has("verdict")).isFalse();
    }

    @Test
    void missingAtParameterUsesInjectedClock() {
        APIGatewayProxyResponseEvent response = call(List.of(NO_PARKING), Map.of(
                "lat", "37.7749", "lng", "-122.4194"));

        assertThat(body(response).get("verdict").asText()).isEqualTo("NOT_PARKABLE");
    }

    @Test
    void noStoredRulesReturns404NoData() {
        APIGatewayProxyResponseEvent response = call(List.of(), Map.of(
                "lat", "37.7749", "lng", "-122.4194"));

        assertThat(response.getStatusCode()).isEqualTo(404);
        assertThat(body(response).get("status").asText()).isEqualTo("NO_DATA");
    }

    @Test
    void missingOrInvalidParamsReturn400() {
        assertThat(call(List.of(NO_PARKING), Map.of()).getStatusCode()).isEqualTo(400);
        assertThat(call(List.of(NO_PARKING), Map.of("lat", "abc", "lng", "1")).getStatusCode()).isEqualTo(400);
        assertThat(call(List.of(NO_PARKING), Map.of("lat", "91", "lng", "0")).getStatusCode()).isEqualTo(400);
        assertThat(call(List.of(NO_PARKING),
                Map.of("lat", "1", "lng", "1", "at", "yesterday")).getStatusCode()).isEqualTo(400);
    }

    @Test
    void nullQueryParametersMapIsA400NotACrash() {
        CheckHandler handler = new CheckHandler((lat, lng, radius) -> List.of(NO_PARKING), FIXED_CLOCK);

        APIGatewayProxyResponseEvent response =
                handler.handleRequest(new APIGatewayProxyRequestEvent(), null);

        assertThat(response.getStatusCode()).isEqualTo(400);
    }
}
