package com.parkable.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

/**
 * Applies on an explicit set of weekdays. An empty set is legal but never
 * matches — the evaluator must treat it as a degenerate "never" rather than
 * scanning forever.
 */
public record SpecificDays(Set<DayOfWeek> days) implements DayPattern {

    public SpecificDays {
        days = Set.copyOf(days);
    }

    /** schema.md's "any_day": simply all seven days, not a separate type. */
    public static SpecificDays anyDay() {
        return new SpecificDays(EnumSet.allOf(DayOfWeek.class));
    }

    @Override
    public boolean matches(LocalDate date) {
        return days.contains(date.getDayOfWeek());
    }
}
