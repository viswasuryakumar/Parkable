package com.parkable.model;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import static java.time.DayOfWeek.*;
import static org.assertj.core.api.Assertions.assertThat;

class DayPatternTest {

    @Test
    void specificDaysWeekdaysOnlyMatchesWeekdaysExcludesWeekend() {
        DayPattern weekdays = new SpecificDays(Set.of(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY));
        // 2026-07-13 is a Monday
        assertThat(weekdays.matches(LocalDate.of(2026, 7, 13))).isTrue();   // Mon
        assertThat(weekdays.matches(LocalDate.of(2026, 7, 17))).isTrue();   // Fri
        assertThat(weekdays.matches(LocalDate.of(2026, 7, 18))).isFalse();  // Sat
        assertThat(weekdays.matches(LocalDate.of(2026, 7, 19))).isFalse();  // Sun
    }

    @Test
    void specificDaysEmptySetNeverMatches() {
        DayPattern never = new SpecificDays(Set.of());
        assertThat(never.matches(LocalDate.of(2026, 7, 13))).isFalse();
    }

    @Test
    void anyDayFactoryMatchesAllSevenDays() {
        DayPattern any = SpecificDays.anyDay();
        LocalDate monday = LocalDate.of(2026, 7, 13);
        for (int i = 0; i < 7; i++) {
            assertThat(any.matches(monday.plusDays(i))).isTrue();
        }
    }

    @Test
    void firstAndThirdTuesdayInFiveTuesdayMonth() {
        // September 2026 has five Tuesdays: 1, 8, 15, 22, 29
        DayPattern pattern = new NthWeekdayOfMonth(TUESDAY, Set.of(1, 3));
        assertThat(pattern.matches(LocalDate.of(2026, 9, 1))).isTrue();
        assertThat(pattern.matches(LocalDate.of(2026, 9, 8))).isFalse();
        assertThat(pattern.matches(LocalDate.of(2026, 9, 15))).isTrue();
        assertThat(pattern.matches(LocalDate.of(2026, 9, 22))).isFalse();
        assertThat(pattern.matches(LocalDate.of(2026, 9, 29))).isFalse();
    }

    @Test
    void nthWeekdayNeverMatchesWrongWeekday() {
        DayPattern pattern = new NthWeekdayOfMonth(TUESDAY, Set.of(1, 3));
        assertThat(pattern.matches(LocalDate.of(2026, 9, 2))).isFalse();  // 1st Wednesday
    }

    @Test
    void lastFridaySentinelInFourFridayMonth() {
        // June 2026 Fridays: 5, 12, 19, 26 -> last is also the 4th
        DayPattern lastFriday = new NthWeekdayOfMonth(FRIDAY, Set.of(-1));
        assertThat(lastFriday.matches(LocalDate.of(2026, 6, 26))).isTrue();
        assertThat(lastFriday.matches(LocalDate.of(2026, 6, 19))).isFalse();
    }

    @Test
    void lastFridaySentinelInFiveFridayMonth() {
        // May 2026 Fridays: 1, 8, 15, 22, 29 -> last is the 5th
        DayPattern lastFriday = new NthWeekdayOfMonth(FRIDAY, Set.of(-1));
        assertThat(lastFriday.matches(LocalDate.of(2026, 5, 29))).isTrue();
        assertThat(lastFriday.matches(LocalDate.of(2026, 5, 22))).isFalse();
    }

    @Test
    void fourthOccurrenceStillMatchesWhenItIsAlsoTheLast() {
        // June 2026: the 4th Friday (26th) is also the last; a {4} pattern must
        // not be swallowed by "last" handling.
        DayPattern fourthFriday = new NthWeekdayOfMonth(FRIDAY, Set.of(4));
        assertThat(fourthFriday.matches(LocalDate.of(2026, 6, 26))).isTrue();
    }

    @Test
    void leapDayMatchesAsFifthAndLastTuesday() {
        // Feb 2028 is a leap year whose Tuesdays are 1, 8, 15, 22, 29:
        // Feb 29 is both the 5th and the last Tuesday.
        LocalDate leapDay = LocalDate.of(2028, 2, 29);
        assertThat(leapDay.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(new NthWeekdayOfMonth(TUESDAY, Set.of(-1)).matches(leapDay)).isTrue();
        assertThat(new NthWeekdayOfMonth(TUESDAY, Set.of(5)).matches(leapDay)).isTrue();
        assertThat(new NthWeekdayOfMonth(TUESDAY, Set.of(4)).matches(leapDay)).isFalse();
    }
}
