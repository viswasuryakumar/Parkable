package com.parkable.model;

import java.time.LocalDate;

/**
 * Which calendar days a rule applies to. Sealed so the evaluator can reason
 * exhaustively about every possible pattern shape.
 *
 * <p>schema.md's {@code any_day} type is represented as a {@link SpecificDays}
 * containing all seven days (see {@link SpecificDays#anyDay()}) — no separate
 * record needed. schema.md's {@code date_range} type is modelled orthogonally
 * as {@link DateRange} on {@link RuleMetadata}, because effectivity bounds can
 * combine with any day pattern.
 */
public sealed interface DayPattern permits SpecificDays, NthWeekdayOfMonth {
    boolean matches(LocalDate date);
}
