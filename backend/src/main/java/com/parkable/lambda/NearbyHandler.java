package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.parkable.lambda.config.EnvConfig;
import com.parkable.lambda.config.GeoDistance;
import com.parkable.lambda.config.StorageStack;
import com.parkable.lambda.port.RuleLookup;
import com.parkable.lambda.port.StoredRule;
import com.parkable.model.DayPattern;
import com.parkable.model.NthWeekdayOfMonth;
import com.parkable.model.RuleMetadata;
import com.parkable.model.SpecificDays;
import com.parkable.model.TimeWindow;

import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * GET /nearby?lat&lng[&radius] — all stored rules around a point, for the
 * map view. No verdicts here; listing is not deciding.
 */
public class NearbyHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    static final double DEFAULT_RADIUS_METERS = 1000.0;
    static final double MAX_RADIUS_METERS = 2000.0;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    private final RuleLookup lookup;

    /** No-arg constructor AWS Lambda actually invokes in production; wires from env vars (D4). */
    public NearbyHandler() {
        this(StorageStack.from(EnvConfig.fromEnvironment()).lookup());
    }

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
                    .map(stored -> summarize(stored, latitude, longitude))
                    .toList();
            return Responses.json(200, Map.of("rules", rules));
        } catch (QueryParams.BadRequestException e) {
            return Responses.badRequest(e.getMessage());
        }
    }

    private static Map<String, Object> summarize(StoredRule stored, double queryLat, double queryLng) {
        RuleMetadata metadata = stored.rule().metadata();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rule_id", metadata.ruleId());
        summary.put("description", metadata.description());
        summary.put("source", stored.source());
        summary.put("parser_version", stored.parserVersion());
        summary.put("scan_id", stored.scanId());
        summary.put("days", formatDays(metadata.dayPattern()));
        summary.put("hours", formatHours(metadata.timeWindows()));
        summary.put("lat", stored.latitude());
        summary.put("lng", stored.longitude());
        summary.put("distance_m", Math.round(
                GeoDistance.metersBetween(queryLat, queryLng, stored.latitude(), stored.longitude())));
        return summary;
    }

    /** No day_pattern data on the sign (or "any_day"/all seven) reads as "Every day", never a guess. */
    static String formatDays(DayPattern pattern) {
        return switch (pattern) {
            case SpecificDays specificDays -> specificDays.days().size() == 7
                    ? "Every day"
                    : java.util.stream.Stream.of(DayOfWeek.values())
                            .filter(specificDays.days()::contains)
                            .map(day -> day.getDisplayName(TextStyle.SHORT, Locale.US))
                            .collect(Collectors.joining(", "));
            case NthWeekdayOfMonth nth -> nth.occurrences().stream()
                    .sorted()
                    .map(NearbyHandler::ordinal)
                    .collect(Collectors.joining(" & "))
                    + " " + nth.weekday().getDisplayName(TextStyle.SHORT, Locale.US);
        };
    }

    /** No time_windows on the sign means the schema.md convention: any time. */
    static String formatHours(List<TimeWindow> windows) {
        if (windows.isEmpty()) {
            return "Any time";
        }
        return windows.stream()
                .map(w -> w.start().format(TIME_FORMAT) + "–" + w.end().format(TIME_FORMAT))
                .collect(Collectors.joining(", "));
    }

    private static String ordinal(int occurrence) {
        if (occurrence == NthWeekdayOfMonth.LAST) {
            return "Last";
        }
        int rem100 = occurrence % 100;
        if (rem100 >= 11 && rem100 <= 13) {
            return occurrence + "th";
        }
        return switch (occurrence % 10) {
            case 1 -> occurrence + "st";
            case 2 -> occurrence + "nd";
            case 3 -> occurrence + "rd";
            default -> occurrence + "th";
        };
    }
}
