package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.builder.RuleBuilder;
import com.parkable.lambda.port.StoredRule;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class NearbyHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 37.77, -122.42 is ~880m west of the query point (37.77, -122.41) -
    // a known real-world-ish distance to assert distance_m against.
    private static final StoredRule STORED = new StoredRule(
            new RuleBuilder().permit("A").withId("zone-a").withDescription("Permit zone A").build(),
            "gov_data", "sfmta-etl-v1", 37.77, -122.42, "gov-scan-1", "gov-scan-1");

    @Test
    void listsRuleSummariesWithoutVerdicts() throws Exception {
        NearbyHandler handler = new NearbyHandler((lat, lng, radius) -> List.of(STORED));

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                new APIGatewayProxyRequestEvent().withQueryStringParameters(
                        Map.of("lat", "37.77", "lng", "-122.41")), null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        JsonNode rules = MAPPER.readTree(response.getBody()).get("rules");
        assertThat(rules).hasSize(1);
        JsonNode rule = rules.get(0);
        assertThat(rule.get("rule_id").asText()).isEqualTo("zone-a");
        assertThat(rule.get("source").asText()).isEqualTo("gov_data");
        assertThat(rule.get("parser_version").asText()).isEqualTo("sfmta-etl-v1");
        assertThat(rule.get("scan_id").asText()).isEqualTo("gov-scan-1");
        assertThat(rule.get("days").asText()).isEqualTo("Every day");
        assertThat(rule.get("hours").asText()).isEqualTo("Any time");
        assertThat(rule.get("lat").asDouble()).isEqualTo(37.77);
        assertThat(rule.get("lng").asDouble()).isEqualTo(-122.42);
        assertThat(rule.get("distance_m").asDouble()).isCloseTo(880.0, offset(5.0));
        assertThat(response.getBody()).doesNotContain("verdict");
        // gov_data's photoReference is a meaningless reused extraction id
        // (no real photo exists) - must never attempt to resolve one.
        assertThat(rule.get("photo_url").isNull()).isTrue();
    }

    @Test
    void resolvesAPhotoUrlForCameraScanRowsButNeverForGovData() throws Exception {
        StoredRule camera = new StoredRule(
                new RuleBuilder().noParking().withId("cam-1").build(),
                "camera_scan", "test-parser-v1", 37.77, -122.42, "scan-1", "scan/photo-1.jpg");
        PhotoUrlResolver fakeResolver = photoReference -> java.util.Optional.of("https://photos.example/" + photoReference);
        NearbyHandler handler = new NearbyHandler((lat, lng, radius) -> List.of(camera, STORED), fakeResolver);

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                new APIGatewayProxyRequestEvent().withQueryStringParameters(
                        Map.of("lat", "37.77", "lng", "-122.41")), null);

        JsonNode rules = MAPPER.readTree(response.getBody()).get("rules");
        JsonNode cameraRule = rules.get(0).get("source").asText().equals("camera_scan") ? rules.get(0) : rules.get(1);
        JsonNode govRule = rules.get(0).get("source").asText().equals("gov_data") ? rules.get(0) : rules.get(1);
        assertThat(cameraRule.get("photo_url").asText()).isEqualTo("https://photos.example/scan/photo-1.jpg");
        assertThat(govRule.get("photo_url").isNull()).isTrue();
    }

    @Test
    void formatsSpecificDaysAndTimeWindows() {
        String days = NearbyHandler.formatDays(new com.parkable.model.SpecificDays(
                java.util.Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)));
        assertThat(days).isEqualTo("Mon, Wed, Fri");

        String hours = NearbyHandler.formatHours(
                List.of(new com.parkable.model.TimeWindow(LocalTime.of(8, 0), LocalTime.of(18, 0))));
        assertThat(hours).isEqualTo("8:00 AM–6:00 PM");

        assertThat(NearbyHandler.formatHours(List.of())).isEqualTo("Any time");
    }

    @Test
    void formatsNthWeekdayOfMonthWithOrdinalsAndLast() {
        String firstAndThird = NearbyHandler.formatDays(
                new com.parkable.model.NthWeekdayOfMonth(DayOfWeek.TUESDAY, java.util.Set.of(1, 3)));
        assertThat(firstAndThird).isEqualTo("1st & 3rd Tue");

        String last = NearbyHandler.formatDays(new com.parkable.model.NthWeekdayOfMonth(
                DayOfWeek.FRIDAY, java.util.Set.of(com.parkable.model.NthWeekdayOfMonth.LAST)));
        assertThat(last).isEqualTo("Last Fri");
    }

    @Test
    void radiusDefaultsTo300AndIsCappedAt2000() {
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
