package com.parkable.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Temporal and directional facts shared by every rule type. Concrete rules
 * HAVE-A metadata (composition) rather than extending a base class.
 *
 * <p>An empty {@code timeWindows} list means ANY TIME (schema.md: absent or
 * empty time_windows). Multiple windows model signs like
 * "8am-noon AND 2pm-6pm"; the rule is active when the instant falls in ANY of
 * them.
 */
public record RuleMetadata(
        String ruleId,
        String description,
        DayPattern dayPattern,
        List<TimeWindow> timeWindows,
        Optional<DateRange> dateRange,
        HolidayPolicy holidayPolicy,
        DirectionalModifier direction
) {
    public RuleMetadata {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(dayPattern, "dayPattern");
        timeWindows = List.copyOf(timeWindows);
        Objects.requireNonNull(dateRange, "dateRange");
        Objects.requireNonNull(holidayPolicy, "holidayPolicy");
        Objects.requireNonNull(direction, "direction");
    }

    public boolean isAnyTime() {
        return timeWindows.isEmpty();
    }
}
