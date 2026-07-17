package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.parkable.lambda.port.RuleLookup;
import com.parkable.lambda.port.StoredRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * GET /nearby?lat&lng[&radius] — all stored rules around a point, for the
 * map view. No verdicts here; listing is not deciding.
 */
public class NearbyHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    static final double DEFAULT_RADIUS_METERS = 1000.0;
    static final double MAX_RADIUS_METERS = 2000.0;

    private final RuleLookup lookup;

    public NearbyHandler(RuleLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        Map<String, String> params = event.getQueryStringParameters() == null
                ? Map.of()
                : event.getQueryStringParameters();
        try {
            double latitude = QueryParams.requireDouble(params, "lat");
            double longitude = QueryParams.requireDouble(params, "lng");
            QueryParams.requireValidCoordinates(latitude, longitude);
            double radius = params.containsKey("radius")
                    ? Math.min(QueryParams.requireDouble(params, "radius"), MAX_RADIUS_METERS)
                    : DEFAULT_RADIUS_METERS;

            List<Map<String, Object>> rules = lookup.findWithin(latitude, longitude, radius).stream()
                    .map(NearbyHandler::summarize)
                    .toList();
            return Responses.json(200, Map.of("rules", rules));
        } catch (QueryParams.BadRequestException e) {
            return Responses.badRequest(e.getMessage());
        }
    }

    private static Map<String, Object> summarize(StoredRule stored) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rule_id", stored.rule().metadata().ruleId());
        summary.put("description", stored.rule().metadata().description());
        summary.put("source", stored.source());
        summary.put("parser_version", stored.parserVersion());
        return summary;
    }
}
