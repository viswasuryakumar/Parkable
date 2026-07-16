package com.parkable.model;

/**
 * Whether the rule is suspended on holidays ("Except Holidays" signs).
 * Which calendar defines "holiday" is the evaluator's collaborator
 * ({@code HolidayCalendar}), not per-rule data, until a second calendar
 * actually exists.
 */
public record HolidayPolicy(boolean suspendedOnHolidays) {

    public static HolidayPolicy suspended() {
        return new HolidayPolicy(true);
    }

    public static HolidayPolicy notSuspended() {
        return new HolidayPolicy(false);
    }
}
