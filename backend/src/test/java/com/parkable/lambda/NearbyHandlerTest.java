package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.builder.RuleBuilder;
import com.parkable.lambda.port.StoredRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NearbyHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final StoredRule STORED = new StoredRule(
            new RuleBuilder().permit("A").withId("zone-a").withDescription("Permit zone A").build(),
            "gov_data", "sfmta-etl-v1");

    @Test
    void listsRuleSummariesWithoutVerdicts() throws Exception {
        NearbyHandler handler = new NearbyHandler((lat, lng, radius) -> List.of(STORED));

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                new APIGatewayProxyRequestEvent().withQueryStringParameters(
                        Map.of("lat", "37.77", "lng", "-122.41")), null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        JsonNode rules = MAPPER.readTree(response.getBody()).get("rules");
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).get("rule_id").asText()).isEqualTo("zone-a");
        assertThat(rules.get(0).get("source").asText()).isEqualTo("gov_data");
        assertThat(rules.get(0).get("parser_version").asText()).isEqualTo("sfmta-etl-v1");
        assertThat(response.getBody()).doesNotContain("verdict");
    }

    @Test
    void radiusDefaultsTo1000AndIsCappedAt2000() {
        AtomicReference<Double> seenRadius = new AtomicReference<>();
        NearbyHandler handler = new NearbyHandler((lat, lng, radius) -> {
            seenRadius.set(radius);
            return List.of();
        });

        handler.handleRequest(new APIGatewayProxyRequestEvent()
                .withQueryStringParameters(Map.of("lat", "1", "lng", "1")), null);
        assertThat(seenRadius.get()).isEqualTo(NearbyHandler.DEFAULT_RADIUS_METERS);

        handler.handleRequest(new APIGatewayProxyRequestEvent()
                .withQueryStringParameters(Map.of("lat", "1", "lng", "1", "radius", "99999")), null);
        assertThat(seenRadius.get()).isEqualTo(NearbyHandler.MAX_RADIUS_METERS);
    }

    @Test
    void invalidCoordinatesReturn400() {
        NearbyHandler handler = new NearbyHandler((lat, lng, radius) -> List.of());

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                new APIGatewayProxyRequestEvent().withQueryStringParameters(
                        Map.of("lat", "notanumber", "lng", "0")), null);

        assertThat(response.getStatusCode()).isEqualTo(400);
    }
}
