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
            "gov_data", "sfmta-etl-v1");

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
