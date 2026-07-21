package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkable.model.DirectionalModifier;
import com.parkable.model.HolidayPolicy;
import com.parkable.model.RuleMetadata;
import com.parkable.model.SpecificDays;
import com.parkable.model.TimeWindow;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class GovMapperSupport {
    private GovMapperSupport() {}

    static JsonNode attributes(JsonNode record) {
        return record.path("attributes").isObject() ? record.path("attributes") : record;
    }

    static Optional<String> text(JsonNode record, String field) {
        JsonNode value = attributes(record).path(field);
        if (!value.isTextual() && !value.isNumber()) {
            return Optional.empty();
        }
        String text = value.asText().strip();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    static RuleMetadata metadata(String id, String description, Set<DayOfWeek> days, List<TimeWindow> windows) {
        return new RuleMetadata(id, description, new SpecificDays(days), windows, Optional.empty(),
                HolidayPolicy.notSuspended(), DirectionalModifier.unspecified());
    }

    static Optional<TimeWindow> militaryWindow(JsonNode record, String startField, String endField) {
        JsonNode start = attributes(record).path(startField);
        JsonNode end = attributes(record).path(endField);
        try {
            int startValue = Integer.parseInt(start.asText());
            int endValue = Integer.parseInt(end.asText());
            return time(startValue).flatMap(s -> time(endValue).map(e -> new TimeWindow(s, e)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static Optional<LocalTime> time(int hhmm) {
        if (hhmm == 2400) {
            return Optional.of(LocalTime.MIDNIGHT);
        }
        int hour = hhmm / 100;
        int minute = hhmm % 100;
        if (hour > 23 || minute > 59) {
            return Optional.empty();
        }
        return Optional.of(LocalTime.of(hour, minute));
    }

    static Optional<Set<DayOfWeek>> compactDays(String value) {
        String days = value.strip().toUpperCase();
        if (days.isEmpty()) {
            return Optional.empty();
        }
        if (days.equals("M-SA")) {
            return Optional.of(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SATURDAY));
        }
        if (days.equals("M-F")) {
            return Optional.of(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
        }
        if (days.equals("SA-SU")) {
            return Optional.of(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        }
        return Optional.empty();
    }
}
