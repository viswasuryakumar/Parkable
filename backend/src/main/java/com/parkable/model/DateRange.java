package com.parkable.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Effectivity bounds for seasonal rules (schema.md day_pattern
 * effective_date / sunset_date). Both bounds are inclusive; an absent sunset
 * means the rule never expires.
 */
public record DateRange(LocalDate effectiveDate, Optional<LocalDate> sunsetDate) {

    public DateRange {
        Objects.requireNonNull(effectiveDate, "effectiveDate");
        Objects.requireNonNull(sunsetDate, "sunsetDate");
        sunsetDate.ifPresent(sunset -> {
            if (sunset.isBefore(effectiveDate)) {
                throw new IllegalArgumentException(
                        "sunsetDate " + sunset + " precedes effectiveDate " + effectiveDate);
            }
        });
    }

    public static DateRange from(LocalDate effectiveDate) {
        return new DateRange(effectiveDate, Optional.empty());
    }

    public static DateRange between(LocalDate effectiveDate, LocalDate sunsetDate) {
        return new DateRange(effectiveDate, Optional.of(sunsetDate));
    }

    public boolean includes(LocalDate date) {
        if (date.isBefore(effectiveDate)) {
            return false;
        }
        return sunsetDate.map(sunset -> !date.isAfter(sunset)).orElse(true);
    }
}
