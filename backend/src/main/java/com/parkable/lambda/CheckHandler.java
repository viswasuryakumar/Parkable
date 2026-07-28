package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.parkable.calendar.UsFederalHolidayCalendar;
import com.parkable.engine.RulesEngine;
import com.parkable.engine.TemporalRuleEvaluator;
import com.parkable.lambda.config.EnvConfig;
import com.parkable.lambda.config.GeoDistance;
import com.parkable.lambda.config.StorageStack;
import com.parkable.lambda.port.RuleLookup;
import com.parkable.lambda.port.StoredRule;
import com.parkable.model.Rule;
import com.parkable.model.Side;
import com.parkable.model.VerdictResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.AbstractMap.SimpleEntry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * GET /check?lat&lng — the fast path: answer from stored rules (gov data or
 * geospatial cache) within 25m. Zero business logic: parse, look up,
 * delegate to the engine, format (architecture rule #1).
 */
public class CheckHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    static final double CHECK_RADIUS_METERS = 25.0;

    private final RuleLookup lookup;
    private final RulesEngine engine;
    private final Clock clock;
    private final PhotoUrlResolver photoUrlResolver;

    /** No-arg constructor AWS Lambda actually invokes in production; wires from env vars (D4). */
    public CheckHandler() {
        this(StorageStack.from(EnvConfig.fromEnvironment()).lookup(), Clock.systemUTC(), S3PhotoUrlResolver.fromEnvironment());
    }

    public CheckHandler(RuleLookup lookup, Clock clock) {
        this(lookup, clock, photoReference -> Optional.empty());
    }

    public CheckHandler(RuleLookup lookup, Clock clock, PhotoUrlResolver photoUrlResolver) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.photoUrlResolver = Objects.requireNonNull(photoUrlResolver, "photoUrlResolver");
        this.engine = new RulesEngine(new TemporalRuleEvaluator(new UsFederalHolidayCalendar()));
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
            // The handler edge is the one legitimate wall-clock read; --at
            // keeps every response reproducible on demand.
            Instant at = QueryParams.optionalInstant(params.get("at"), "at").orElseGet(clock::instant);
            ZoneId zone = QueryParams.zoneOrDefault(params.get("zone"));
            Optional<Side> side = QueryParams.optionalSide(params.get("side"));

            List<StoredRule> stored = lookup.findWithin(latitude, longitude, CHECK_RADIUS_METERS);
            if (stored.isEmpty()) {
                return Responses.noData();
            }

            Map<String, List<StoredRule>> bySign = stored.stream()
                    .collect(Collectors.groupingBy(StoredRule::scanId, LinkedHashMap::new, Collectors.toList()));

            if (bySign.size() == 1) {
                List<StoredRule> signRules = bySign.values().iterator().next();
                List<Rule> rules = signRules.stream().map(StoredRule::rule).toList();
                VerdictResult result = engine.evaluate(rules, at, zone, side);
                Optional<String> photoUrl = photoUrlFor(result, signRules);
                return Responses.verdict(result, signRules, photoUrl, Optional.empty());
            }

            // Several distinct signs (different scanId - a re-scan of the
            // SAME physical sign is already collapsed into one scanId by the
            // content-match supersede at write time) all landed within the
            // 25m radius. Blending their rules into one RulesEngine.evaluate
            // call would silently treat two unrelated signs as one - e.g. a
            // loading-zone sign across the street and the actual curb sign
            // both feeding one verdict - so each gets its own independent
            // evaluation instead, closest first.
            List<Map<String, Object>> signs = bySign.values().stream()
                    .map(signRules -> {
                        List<Rule> rules = signRules.stream().map(StoredRule::rule).toList();
                        VerdictResult result = engine.evaluate(rules, at, zone, side);
                        Optional<String> photoUrl = photoUrlFor(result, signRules);
                        double signLat = signRules.get(0).latitude();
                        double signLng = signRules.get(0).longitude();
                        double distance = GeoDistance.metersBetween(latitude, longitude, signLat, signLng);
                        Entry<Double, Map<String, Object>> entry = new SimpleEntry<>(distance,
                                Responses.signVerdict(result, signRules, photoUrl, signLat, signLng, distance));
                        return entry;
                    })
                    .sorted(Entry.comparingByKey())
                    .map(Entry::getValue)
                    .toList();
            return Responses.multipleSigns(signs);
        } catch (QueryParams.BadRequestException e) {
            return Responses.badRequest(e.getMessage());
        }
    }

    /**
     * Same "which stored rule answered this" logic as Responses.sourceOf -
     * the triggering rule's own StoredRule, or the first one when nothing
     * triggered (all-clear PARKABLE). Only camera_scan rows have a real
     * photo; gov_data's photoReference is a meaningless reused id (see
     * StoredRule's own doc), so resolving one for it would just be a dead
     * link - skip it rather than waste a presign call.
     */
    private Optional<String> photoUrlFor(VerdictResult result, List<StoredRule> stored) {
        return result.triggeringRule()
                .flatMap(match -> stored.stream().filter(s -> s.rule().equals(match.rule())).findFirst())
                .or(() -> stored.stream().findFirst())
                .filter(s -> "camera_scan".equals(s.source()))
                .flatMap(s -> photoUrlResolver.resolve(s.photoReference()));
    }
}
