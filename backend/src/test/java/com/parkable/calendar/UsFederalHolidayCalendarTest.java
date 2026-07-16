package com.parkable.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class UsFederalHolidayCalendarTest {

    private final HolidayCalendar calendar = new UsFederalHolidayCalendar();

    @Test
    void fixedDateHolidayOnWeekday() {
        // 2025-07-04 is a Friday: no observation shift involved
        assertThat(calendar.isHoliday(LocalDate.of(2025, 7, 4))).isTrue();
    }

    @Test
    void ordinaryDayIsNotHoliday() {
        assertThat(calendar.isHoliday(LocalDate.of(2026, 7, 15))).isFalse();
    }

    @Test
    void thanksgivingIsFourthThursdayOfNovember() {
        assertThat(calendar.isHoliday(LocalDate.of(2026, 11, 26))).isTrue();
        // 3rd Thursday is not Thanksgiving
        assertThat(calendar.isHoliday(LocalDate.of(2026, 11, 19))).isFalse();
    }

    @Test
    void mlkIsThirdMondayOfJanuary() {
        assertThat(calendar.isHoliday(LocalDate.of(2026, 1, 19))).isTrue();
        assertThat(calendar.isHoliday(LocalDate.of(2026, 1, 12))).isFalse();
    }

    @Test
    void memorialDayIsLastMondayOfMay() {
        assertThat(calendar.isHoliday(LocalDate.of(2026, 5, 25))).isTrue();
        assertThat(calendar.isHoliday(LocalDate.of(2026, 5, 18))).isFalse();
    }

    @Test
    void laborDayIsFirstMondayOfSeptember() {
        assertThat(calendar.isHoliday(LocalDate.of(2026, 9, 7))).isTrue();
    }

    @Test
    void saturdayHolidayObservedOnPrecedingFriday() {
        // 2027-12-25 falls on a Saturday -> observed Friday 2027-12-24
        assertThat(calendar.isHoliday(LocalDate.of(2027, 12, 24))).isTrue();
        assertThat(calendar.isHoliday(LocalDate.of(2027, 12, 25))).isFalse();
    }

    @Test
    void sundayHolidayObservedOnFollowingMonday() {
        // 2027-07-04 falls on a Sunday -> observed Monday 2027-07-05
        assertThat(calendar.isHoliday(LocalDate.of(2027, 7, 5))).isTrue();
        assertThat(calendar.isHoliday(LocalDate.of(2027, 7, 4))).isFalse();
    }

    @Test
    void newYearsDayObservedInPreviousCalendarYear() {
        // 2028-01-01 falls on a Saturday -> observed Friday 2027-12-31,
        // i.e. the observation crosses the year boundary backwards.
        assertThat(calendar.isHoliday(LocalDate.of(2027, 12, 31))).isTrue();
        assertThat(calendar.isHoliday(LocalDate.of(2028, 1, 1))).isFalse();
    }
}
