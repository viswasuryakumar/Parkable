package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkable.model.NoParkingRule;
import com.parkable.model.Rule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps only NYC sign text that explicitly states both its active days and hours. */
public final class NycSignMapper implements GovRuleMapper {
    private static final Pattern HOURS = Pattern.compile("(?i)(\\d{1,2})(?::(\\d{2}))?\\s*(AM|PM)\\s*(?:-|TO)\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(AM|PM)");

    @Override
    public List<Rule> map(JsonNode rawRecord) {
        Optional<String> description = GovMapperSupport.text(rawRecord, "sign_description");
        Optional<String> id = GovMapperSupport.text(rawRecord, "order_number");
        if (description.isEmpty() || id.isEmpty() || !description.get().toUpperCase().contains("NO PARK")) {
            return List.of();
        }
        Optional<Set<DayOfWeek>> days = days(description.get());
        Optional<com.parkable.model.TimeWindow> window = window(description.get());
        if (days.isEmpty() || window.isEmpty()) {
            return List.of();
        }
        return List.of(new NoParkingRule(GovMapperSupport.metadata("nyc:afgb-4qw7:" + id.get(), description.get(),
                days.get(), List.of(window.get()))));
    }

    @Override
    public String parserVersion() {
        return "gov-nyc-mapper-v1";
    }

    private static Optional<Set<DayOfWeek>> days(String text) {
        String value = text.toUpperCase();
        if (value.contains("MON-FRI") || value.contains("MONDAY-FRIDAY")) {
            return Optional.of(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
        }
        if (value.contains("MON-SAT") || value.contains("MONDAY-SATURDAY")) {
            return Optional.of(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SATURDAY));
        }
        return Optional.empty();
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
}
