package com.parkable.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.MonthDay;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * The Phase 1 holiday set from the plan: three fixed holidays (New Year's Day,
 * Independence Day, Christmas) with federal weekend-observation shifting, plus
 * four floating holidays (MLK, Memorial Day, Labor Day, Thanksgiving).
 *
 * <p>Deliberately not the complete federal list — the plan scopes it to these
 * seven; extending the set is additive and test-driven.
 */
public final class UsFederalHolidayCalendar implements HolidayCalendar {

    private static final List<MonthDay> FIXED = List.of(
            MonthDay.of(Month.JANUARY, 1),
            MonthDay.of(Month.JULY, 4),
            MonthDay.of(Month.DECEMBER, 25));

    @Override
    public boolean isHoliday(LocalDate date) {
        return isObservedFixedHoliday(date) || isFloatingHoliday(date);
    }

    private static boolean isObservedFixedHoliday(LocalDate date) {
        // New Year's Day falling on a Saturday is observed on Dec 31 of the
        // PREVIOUS year, so next year's fixed holidays must be checked too.
        for (int year : new int[]{date.getYear(), date.getYear() + 1}) {
            for (MonthDay fixed : FIXED) {
                if (observed(fixed.atYear(year)).equals(date)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Federal observation practice: Saturday holidays are observed the
     * preceding Friday, Sunday holidays the following Monday. Parking
     * enforcement suspensions follow the observed day, so the shifted date is
     * the holiday and the actual weekend date is not.
     */
    private static LocalDate observed(LocalDate actual) {
        return switch (actual.getDayOfWeek()) {
            case SATURDAY -> actual.minusDays(1);
            case SUNDAY -> actual.plusDays(1);
            default -> actual;
        };
    }

    private static boolean isFloatingHoliday(LocalDate date) {
        int year = date.getYear();
        return date.equals(nthWeekdayOf(year, Month.JANUARY, DayOfWeek.MONDAY, 3))      // MLK Day
                || date.equals(lastWeekdayOf(year, Month.MAY, DayOfWeek.MONDAY))        // Memorial Day
                || date.equals(nthWeekdayOf(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1)) // Labor Day
                || date.equals(nthWeekdayOf(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4)); // Thanksgiving
    }

    private static LocalDate nthWeekdayOf(int year, Month month, DayOfWeek weekday, int n) {
        return LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.dayOfWeekInMonth(n, weekday));
    }

    private static LocalDate lastWeekdayOf(int year, Month month, DayOfWeek weekday) {
        return LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.lastInMonth(weekday));
    }
}
