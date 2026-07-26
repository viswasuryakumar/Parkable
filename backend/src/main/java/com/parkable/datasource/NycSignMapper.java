package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkable.model.NoParkingRule;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps only NYC sign text that explicitly states both its active days and
 * hours (or explicitly names no hours at all, via "ANY TIME"/"ANYTIME").
 *
 * <p>Pulls from Socrata resource {@code nfid-uabd} ("Parking Regulation
 * Locations and Signs," 440k+ rows — the dataset docs/schema.md's own
 * References section documents). An earlier version of this ETL was wired
 * to {@code afgb-4qw7} instead - a small, unrelated 118-row "press parking"
 * feed - so gov-nyc-mapper-v1 silently imported nothing in production
 * despite passing tests (its fixtures never exercised a real resource id).
 */
public final class NycSignMapper implements GovRuleMapper {
    public static final String PARSER_VERSION = "gov-nyc-mapper-v1";
    private static final Pattern HOURS = Pattern.compile("(?i)(\\d{1,2})(?::(\\d{2}))?\\s*(AM|PM)\\s*(?:-|TO)\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(AM|PM)");
    private static final CoordinateTransform STATE_PLANE_TO_WGS84 = statePlaneToWgs84();

    private static final Map<String, DayOfWeek> DAY_NAMES = Map.of(
            "MONDAY", DayOfWeek.MONDAY,
            "TUESDAY", DayOfWeek.TUESDAY,
            "WEDNESDAY", DayOfWeek.WEDNESDAY,
            "THURSDAY", DayOfWeek.THURSDAY,
            "FRIDAY", DayOfWeek.FRIDAY,
            "SATURDAY", DayOfWeek.SATURDAY,
            "SUNDAY", DayOfWeek.SUNDAY);

    @Override
    public List<MappedRule> map(JsonNode rawRecord) {
        Optional<String> description = GovMapperSupport.text(rawRecord, "sign_description");
        Optional<String> id = GovMapperSupport.text(rawRecord, "order_number");
        if (description.isEmpty() || id.isEmpty() || !description.get().toUpperCase().contains("NO PARK")) {
            return List.of();
        }
        Optional<GovMapperSupport.Coordinates> location = location(rawRecord);
        if (location.isEmpty()) {
            return List.of();
        }
        String text = description.get();

        Set<DayOfWeek> ruleDays;
        List<com.parkable.model.TimeWindow> ruleWindows;
        if (isAnyTime(text)) {
            // "NO PARKING ANYTIME" names no hours at all - every day, all
            // hours. schema.md's convention for ANY TIME is an empty
            // time_windows list, not a fabricated 00:00-23:59 window.
            ruleDays = EnumSet.allOf(DayOfWeek.class);
            ruleWindows = List.of();
        } else {
            Optional<Set<DayOfWeek>> days = days(text);
            Optional<com.parkable.model.TimeWindow> window = window(text);
            if (days.isEmpty() || window.isEmpty()) {
                return List.of();
            }
            ruleDays = days.get();
            ruleWindows = List.of(window.get());
        }
        return List.of(new MappedRule(new NoParkingRule(GovMapperSupport.metadata("nyc:nfid-uabd:" + id.get(),
                text, ruleDays, ruleWindows)), location.get().latitude(), location.get().longitude()));
    }

    @Override
    public String parserVersion() {
        return PARSER_VERSION;
    }

    private static boolean isAnyTime(String text) {
        String value = text.toUpperCase();
        return value.contains("ANY TIME") || value.contains("ANYTIME");
    }

    private static Optional<Set<DayOfWeek>> days(String text) {
        String value = text.toUpperCase();
        if (value.contains("MON-FRI") || value.contains("MONDAY-FRIDAY")) {
            return Optional.of(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
        }
        if (value.contains("MON-SAT") || value.contains("MONDAY-SATURDAY")) {
            return Optional.of(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SATURDAY));
        }
        // "NO PARKING 6AM-8AM EXCEPT SUNDAY" means every day EXCLUDING
        // Sunday, not "applies on Sunday" - a plain day-name scan of the
        // whole string would pick up "SUNDAY" from the EXCEPT clause and
        // invert the meaning (found live: an EXCEPT SUNDAY sign imported as
        // "days: Sun" instead of Mon-Sat). Split on EXCEPT first: day names
        // after it are exclusions from a base set (everything named before
        // it, or all seven days if none were).
        int exceptIndex = value.indexOf("EXCEPT");
        if (exceptIndex >= 0) {
            Set<DayOfWeek> excluded = namedDaysIn(value.substring(exceptIndex));
            if (!excluded.isEmpty()) {
                Set<DayOfWeek> base = namedDaysIn(value.substring(0, exceptIndex));
                Set<DayOfWeek> result = base.isEmpty() ? EnumSet.allOf(DayOfWeek.class) : EnumSet.copyOf(base);
                result.removeAll(excluded);
                return result.isEmpty() ? Optional.empty() : Optional.of(result);
            }
            // EXCEPT clause doesn't name a day (e.g. "EXCEPT HOLIDAYS") -
            // falls through to a plain scan of the whole text below.
        }
        // The dominant real pattern (alternate-side street cleaning) names
        // specific individual days, not a range - e.g. "MONDAY THURSDAY
        // 9AM-10:30AM" or a single "TUESDAY". Collect every full day name
        // present rather than assuming a contiguous range.
        Set<DayOfWeek> named = namedDaysIn(value);
        return named.isEmpty() ? Optional.empty() : Optional.of(named);
    }

    private static Set<DayOfWeek> namedDaysIn(String upperCaseText) {
        Set<DayOfWeek> found = EnumSet.noneOf(DayOfWeek.class);
        for (Map.Entry<String, DayOfWeek> entry : DAY_NAMES.entrySet()) {
            if (Pattern.compile("\\b" + entry.getKey() + "\\b").matcher(upperCaseText).find()) {
                found.add(entry.getValue());
            }
        }
        return found;
    }

    private static Optional<com.parkable.model.TimeWindow> window(String text) {
        Matcher match = HOURS.matcher(text);
        if (!match.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new com.parkable.model.TimeWindow(
                    toTime(match.group(1), match.group(2), match.group(3)),
                    toTime(match.group(4), match.group(5), match.group(6))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static LocalTime toTime(String hourText, String minuteText, String amPm) {
        int hour = Integer.parseInt(hourText);
        int minute = minuteText == null ? 0 : Integer.parseInt(minuteText);
        if (hour < 1 || hour > 12 || minute > 59) {
            throw new IllegalArgumentException("invalid sign time");
        }
        if (hour == 12) {
            hour = 0;
        }
        if ("PM".equalsIgnoreCase(amPm)) {
            hour += 12;
        }
        return LocalTime.of(hour, minute);
    }

    private static Optional<GovMapperSupport.Coordinates> location(JsonNode record) {
        try {
            double x = Double.parseDouble(GovMapperSupport.text(record, "sign_x_coord").orElseThrow());
            double y = Double.parseDouble(GovMapperSupport.text(record, "sign_y_coord").orElseThrow());
            ProjCoordinate destination = new ProjCoordinate();
            STATE_PLANE_TO_WGS84.transform(new ProjCoordinate(x, y), destination);
            return GovMapperSupport.coordinates(destination.y, destination.x);
        } catch (NumberFormatException | java.util.NoSuchElementException e) {
            return Optional.empty();
        }
    }

    private static CoordinateTransform statePlaneToWgs84() {
        CRSFactory factory = new CRSFactory();
        CoordinateReferenceSystem statePlane = factory.createFromName("EPSG:2263");
        CoordinateReferenceSystem wgs84 = factory.createFromName("EPSG:4326");
        return new CoordinateTransformFactory().createTransform(statePlane, wgs84);
    }
}
