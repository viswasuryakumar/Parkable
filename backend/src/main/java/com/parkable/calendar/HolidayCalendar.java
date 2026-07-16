package com.parkable.calendar;

import java.time.LocalDate;

/** Seam for swapping holiday definitions (city calendars, test fakes). */
public interface HolidayCalendar {
    boolean isHoliday(LocalDate date);
}
