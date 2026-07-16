package com.parkable.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * Applies on the nth occurrence(s) of a weekday within each month — the shape
 * of most street-cleaning schedules ("1st &amp; 3rd Tuesday").
 *
 * <p>Occurrences 1-5 count from the start of the month; the sentinel -1 means
 * "last occurrence", which is the 4th in some months and the 5th in others.
 */
public record NthWeekdayOfMonth(DayOfWeek weekday, Set<Integer> occurrences) implements DayPattern {

    public static final int LAST = -1;

    public NthWeekdayOfMonth {
        occurrences = Set.copyOf(occurrences);
    }

    @Override
    public boolean matches(LocalDate date) {
        if (date.getDayOfWeek() != weekday) {
            return false;
        }
        int occurrence = (date.getDayOfMonth() - 1) / 7 + 1;
        if (occurrences.contains(occurrence)) {
            return true;
        }
        // A date is the LAST occurrence exactly when the same weekday seven days
        // later lands in the next month. Checked in addition to (not instead of)
        // the numeric occurrence, since e.g. a 4th Friday can also be the last.
        boolean isLast = date.plusDays(7).getMonth() != date.getMonth();
        return isLast && occurrences.contains(LAST);
    }
}
