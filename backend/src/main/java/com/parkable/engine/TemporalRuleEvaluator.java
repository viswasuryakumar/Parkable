package com.parkable.engine;

import com.parkable.calendar.HolidayCalendar;
import com.parkable.model.DateRange;
import com.parkable.model.Rule;
import com.parkable.model.RuleMetadata;
import com.parkable.model.TimeWindow;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Answers, for one rule at one instant: is it active, and when does that
 * answer next change?
 *
 * <p>All zoned instants are built via {@link ZonedDateTime#of}, so the JDK's
 * uniform DST resolution applies everywhere: wall times inside a
 * spring-forward gap shift forward by the gap, and ambiguous fall-back times
 * take the earlier offset. Tests pin both edges so a JDK/tzdata behavior
 * change surfaces as a failure.
 */
public final class TemporalRuleEvaluator {

    /**
     * One leap year of forward scan guarantees any annually recurring pattern
     * (nth weekday, seasonal window) is found if it can ever recur; beyond
     * that we report "no known boundary" instead of looping forever on
     * degenerate patterns.
     */
    private static final int MAX_LOOKAHEAD_DAYS = 366;

    private enum Path { INACTIVE, ANY_TIME, WINDOW_TODAY, YESTERDAY_TAIL }

    private final HolidayCalendar holidays;

    public TemporalRuleEvaluator(HolidayCalendar holidays) {
        this.holidays = holidays;
    }

    public RuleActivation evaluate(Rule rule, ZonedDateTime at) {
        RuleMetadata meta = rule.metadata();
        LocalDate today = at.toLocalDate();
        Path path = activationPath(meta, at);

        if (path == Path.INACTIVE && meta.dateRange().isPresent()) {
            DateRange range = meta.dateRange().get();
            if (today.isBefore(range.effectiveDate())) {
                // Before effectivity the earliest possible status change is the
                // start of the effective date in the evaluation zone — returned
                // directly so a far-future date never triggers a 366-day scan.
                Instant boundary = range.effectiveDate().atStartOfDay(at.getZone()).toInstant();
                return new RuleActivation(false, Optional.of(boundary),
                        "inactive: before effective date " + range.effectiveDate());
            }
            if (range.sunsetDate().isPresent() && today.isAfter(range.sunsetDate().get())) {
                // A dead rule must not pollute the engine's validUntil with a
                // boundary that can never arrive.
                return new RuleActivation(false, Optional.empty(),
                        "inactive: past sunset date " + range.sunsetDate().get());
            }
        }

        boolean active = path != Path.INACTIVE;
        Optional<Instant> boundary = nextBoundary(meta, at, active);
        return new RuleActivation(active, boundary, describe(meta, at, path));
    }

    // -- activation ----------------------------------------------------------

    private Path activationPath(RuleMetadata meta, ZonedDateTime t) {
        LocalDate day = t.toLocalDate();
        LocalTime time = t.toLocalTime();
        if (meta.isAnyTime()) {
            return dayApplies(meta, day) ? Path.ANY_TIME : Path.INACTIVE;
        }
        for (TimeWindow window : meta.timeWindows()) {
            if (window.crossesMidnight()) {
                // A midnight-crossing window belongs to the day it STARTS on:
                // the pre-midnight head needs today's pattern to match, while
                // the post-midnight tail needs YESTERDAY's ("yesterday-tail"
                // spillover — the case naive day-first checks miss).
                if (dayApplies(meta, day) && !time.isBefore(window.start())) {
                    return Path.WINDOW_TODAY;
                }
                if (dayApplies(meta, day.minusDays(1)) && time.isBefore(window.end())) {
                    return Path.YESTERDAY_TAIL;
                }
            } else if (dayApplies(meta, day) && window.contains(time)) {
                return Path.WINDOW_TODAY;
            }
        }
        return Path.INACTIVE;
    }

    private boolean isActiveAt(RuleMetadata meta, ZonedDateTime t) {
        return activationPath(meta, t) != Path.INACTIVE;
    }

    private boolean dayApplies(RuleMetadata meta, LocalDate day) {
        if (meta.dateRange().isPresent() && !meta.dateRange().get().includes(day)) {
            return false;
        }
        if (meta.holidayPolicy().suspendedOnHolidays() && holidays.isHoliday(day)) {
            return false;
        }
        return meta.dayPattern().matches(day);
    }

    // -- next boundary ---------------------------------------------------------

    private Optional<Instant> nextBoundary(RuleMetadata meta, ZonedDateTime at, boolean activeNow) {
        ZoneId zone = at.getZone();
        LocalDate today = at.toLocalDate();

        if (meta.isAnyTime()) {
            // ANY-TIME rules flip only at local midnight, when the day pattern,
            // holiday status, or date-range effectivity can change.
            for (int i = 1; i <= MAX_LOOKAHEAD_DAYS; i++) {
                LocalDate day = today.plusDays(i);
                if (dayApplies(meta, day) != activeNow) {
                    return Optional.of(day.atStartOfDay(zone).toInstant());
                }
            }
            return Optional.empty();
        }

        Instant now = at.toInstant();
        Instant best = null;
        // Start at yesterday: a midnight-crossing window that began yesterday
        // can still end later today.
        for (int i = -1; i <= MAX_LOOKAHEAD_DAYS; i++) {
            LocalDate day = today.plusDays(i);
            // Every candidate produced by a given day is at or after that day's
            // local midnight, so once a day starts later than the current best
            // no better candidate can appear.
            if (best != null && day.atStartOfDay(zone).toInstant().isAfter(best)) {
                break;
            }
            if (!dayApplies(meta, day)) {
                continue;
            }
            for (TimeWindow window : meta.timeWindows()) {
                Instant start = ZonedDateTime.of(day, window.start(), zone).toInstant();
                LocalDate endDay = window.crossesMidnight() ? day.plusDays(1) : day;
                Instant end = ZonedDateTime.of(endDay, window.end(), zone).toInstant();
                best = better(meta, zone, now, activeNow, best, start);
                best = better(meta, zone, now, activeNow, best, end);
            }
        }
        return Optional.ofNullable(best);
    }

    private Instant better(RuleMetadata meta, ZoneId zone, Instant now, boolean activeNow,
                           Instant best, Instant candidate) {
        if (!candidate.isAfter(now)) {
            return best;
        }
        // An edge where the rule stays in its current state is not a boundary:
        // adjacent windows (A ends exactly when B starts) must not produce a
        // spurious validUntil, and a zero-duration window has no effect at all.
        if (isActiveAt(meta, candidate.atZone(zone)) == activeNow) {
            return best;
        }
        return (best == null || candidate.isBefore(best)) ? candidate : best;
    }

    // -- reasons -----------------------------------------------------------------

    private String describe(RuleMetadata meta, ZonedDateTime at, Path path) {
        return switch (path) {
            case ANY_TIME -> "active: day pattern matches; rule applies at any time";
            case WINDOW_TODAY -> "active: within a time window on a matching day";
            case YESTERDAY_TAIL -> "active: previous day's midnight-crossing window extends past midnight";
            case INACTIVE -> {
                LocalDate today = at.toLocalDate();
                if (meta.holidayPolicy().suspendedOnHolidays() && holidays.isHoliday(today)) {
                    yield "inactive: suspended on holiday";
                }
                if (!meta.dayPattern().matches(today)) {
                    yield "inactive: day pattern does not match today";
                }
                yield "inactive: outside time window(s)";
            }
        };
    }
}
