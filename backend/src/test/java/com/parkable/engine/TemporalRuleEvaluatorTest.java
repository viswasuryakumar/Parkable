package com.parkable.engine;

import com.parkable.builder.RuleBuilder;
import com.parkable.calendar.UsFederalHolidayCalendar;
import com.parkable.model.Rule;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static java.time.DayOfWeek.*;
import static org.assertj.core.api.Assertions.assertThat;

class TemporalRuleEvaluatorTest {

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");

    /** No-holiday calendar isolates temporal logic from holiday logic. */
    private final TemporalRuleEvaluator evaluator = new TemporalRuleEvaluator(date -> false);
    private final TemporalRuleEvaluator withHolidays = new TemporalRuleEvaluator(new UsFederalHolidayCalendar());

    private static ZonedDateTime la(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(LocalDateTime.of(year, month, day, hour, minute), LA);
    }

    // -- basic activation ---------------------------------------------------

    @Test
    void anyTimeAllDaysRuleIsAlwaysActiveWithNoBoundary() {
        Rule rule = new RuleBuilder().noParking().onAnyDay().anyTime().build();
        RuleActivation activation = evaluator.evaluate(rule, la(2026, 7, 15, 10, 0));
        assertThat(activation.active()).isTrue();
        assertThat(activation.nextBoundary()).isEmpty();
    }

    @Test
    void activeMidWindowBoundaryIsWindowEndToday() {
        Rule rule = new RuleBuilder().timeLimit(Duration.ofHours(2))
                .onDays(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
                .duringWindow(LocalTime.of(8, 0), LocalTime.of(18, 0)).build();
        // Wednesday 2026-07-15 10:00 PDT
        RuleActivation activation = evaluator.evaluate(rule, la(2026, 7, 15, 10, 0));
        assertThat(activation.active()).isTrue();
        assertThat(activation.nextBoundary()).contains(Instant.parse("2026-07-16T01:00:00Z")); // 18:00 PDT
    }

    @Test
    void inactiveBeforeTodaysWindowBoundaryIsWindowStartToday() {
        Rule rule = new RuleBuilder().noParking().onAnyDay()
                .duringWindow(LocalTime.of(18, 0), LocalTime.of(20, 0)).build();
        RuleActivation activation = evaluator.evaluate(rule, la(2026, 7, 15, 10, 0));
        assertThat(activation.active()).isFalse();
        assertThat(activation.nextBoundary()).contains(Instant.parse("2026-07-16T01:00:00Z")); // 18:00 PDT
    }

    @Test
    void inactiveOnWrongDayBoundaryIsNextMatchingDaysWindowStart() {
        Rule rule = new RuleBuilder().noParking().onDays(TUESDAY)
                .duringWindow(LocalTime.of(8, 0), LocalTime.of(10, 0)).build();
        // Wednesday: next Tuesday is 2026-07-21
        RuleActivation activation = evaluator.evaluate(rule, la(2026, 7, 15, 10, 0));
        assertThat(activation.active()).isFalse();
        assertThat(activation.nextBoundary()).contains(Instant.parse("2026-07-21T15:00:00Z")); // 08:00 PDT
    }

    @Test
    void anyTimeWeekdayRuleBoundaryIsMidnightOfFirstNonMatchingDay() {
        Rule rule = new RuleBuilder().noParking()
                .onDays(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY).anyTime().build();
        // Wednesday -> flips off at Saturday midnight
        RuleActivation activation = evaluator.evaluate(rule, la(2026, 7, 15, 10, 0));
        assertThat(activation.active()).isTrue();
        assertThat(activation.nextBoundary()).contains(Instant.parse("2026-07-18T07:00:00Z")); // Sat 00:00 PDT
    }

    // -- midnight crossing --------------------------------------------------

    @Nested
    class MidnightCrossing {

        private final Rule mondayLateNight = new RuleBuilder().noParking().onDays(MONDAY)
                .duringWindow(LocalTime.of(23, 0), LocalTime.of(2, 0)).build();

        @Test
        void activeInPreMidnightHeadOnMatchingDay() {
            // Monday 2026-07-13 23:30
            RuleActivation activation = evaluator.evaluate(mondayLateNight, la(2026, 7, 13, 23, 30));
            assertThat(activation.active()).isTrue();
            assertThat(activation.nextBoundary()).contains(Instant.parse("2026-07-14T09:00:00Z")); // Tue 02:00 PDT
        }

        @Test
        void yesterdayTailSpilloverKeepsMondayRuleActiveTuesdayEarlyMorning() {
            // Tuesday 01:00: Monday's 23:00-02:00 window is still running even
            // though Tuesday itself never matches the day pattern.
            RuleActivation activation = evaluator.evaluate(mondayLateNight, la(2026, 7, 14, 1, 0));
            assertThat(activation.active()).isTrue();
            assertThat(activation.reason()).containsIgnoringCase("previous day");
            assertThat(activation.nextBoundary()).contains(Instant.parse("2026-07-14T09:00:00Z")); // Tue 02:00 PDT
        }

        @Test
        void inactiveAfterTailEndsBoundaryIsNextMondaysWindowStart() {
            // Tuesday 03:00: tail is over; next activation is next Monday 23:00
            RuleActivation activation = evaluator.evaluate(mondayLateNight, la(2026, 7, 14, 3, 0));
            assertThat(activation.active()).isFalse();
            assertThat(activation.nextBoundary()).contains(Instant.parse("2026-07-21T06:00:00Z")); // Mon 23:00 PDT
        }
    }

    // -- nth weekday --------------------------------------------------------

    @Test
    void nthWeekdayRuleInactiveOnNonQualifyingTuesday() {
        Rule rule = new RuleBuilder().noParking().onNthWeekday(TUESDAY, 1, 3)
                .duringWindow(LocalTime.of(8, 0), LocalTime.of(10, 0)).build();
        // 2026-09-08 is the 2nd Tuesday; boundary jumps a week to the 3rd (Sep 15)
        RuleActivation activation = evaluator.evaluate(rule, la(2026, 9, 8, 9, 0));
        assertThat(activation.active()).isFalse();
        assertThat(activation.nextBoundary()).contains(Instant.parse("2026-09-15T15:00:00Z")); // 08:00 PDT
    }

    @Test
    void nthWeekdayRuleActiveOnQualifyingTuesday() {
        Rule rule = new RuleBuilder().noParking().onNthWeekday(TUESDAY, 1, 3)
                .duringWindow(LocalTime.of(8, 0), LocalTime.of(10, 0)).build();
        RuleActivation activation = evaluator.evaluate(rule, la(2026, 9, 1, 9, 0));
        assertThat(activation.active()).isTrue();
        assertThat(activation.nextBoundary()).contains(Instant.parse("2026-09-01T17:00:00Z")); // 10:00 PDT
    }

    // -- DST edges ----------------------------------------------------------

    @Nested
    class DaylightSaving {

        @Test
        void springForwardWindowEndInGapResolvesForwardDeterministically() {
            // 2026-03-08 (Sunday) 02:00-03:00 PST does not exist in LA. A window
            // ending 02:30 gets resolved by the JDK to 03:30 PDT; this test pins
            // that behavior so a JDK/tzdata change surfaces as a failure.
            Rule rule = new RuleBuilder().noParking().onDays(SUNDAY)
                    .duringWindow(LocalTime.of(1, 0), LocalTime.of(2, 30)).build();
            ZonedDateTime at = la(2026, 3, 8, 1, 30); // 01:30 PST = 09:30Z
            RuleActivation activation = evaluator.evaluate(rule, at);
            assertThat(activation.active()).isTrue();
            assertThat(activation.nextBoundary()).contains(Instant.parse("2026-03-08T10:30:00Z")); // 03:30 PDT
            // The gap swallowed an hour: only 60 real minutes remain of a
            // nominally 90-minute window.
            assertThat(Duration.between(at.toInstant(), activation.nextBoundary().orElseThrow()))
                    .isEqualTo(Duration.ofHours(1));
        }

        @Test
        void fallBackFirstPassThroughRepeatedHourEndsAtEarlierOffset() {
            // 2026-11-01 (Sunday): 01:00-02:00 wall time happens twice in LA.
            // First pass (PDT): the 01:30 window end resolves to the earlier
            // offset, 30 real minutes ahead.
            Rule rule = new RuleBuilder().noParking().onDays(SUNDAY)
                    .duringWindow(LocalTime.of(0, 30), LocalTime.of(1, 30)).build();
            ZonedDateTime at = Instant.parse("2026-11-01T08:00:00Z").atZone(LA); // 01:00 PDT
            RuleActivation activation = evaluator.evaluate(rule, at);
            assertThat(activation.active()).isTrue();
            assertThat(activation.nextBoundary()).contains(Instant.parse("2026-11-01T08:30:00Z")); // 01:30 PDT
        }

        @Test
        void fallBackSecondPassNeverYieldsBoundaryBeforeEvaluatedInstant() {
            // Second pass (PST): wall clock reads 01:00 again, inside the window,
            // but the JDK-resolved window end (01:30 at the EARLIER offset) is
            // already in the past. The evaluator must not emit a stale boundary;
            // the next status change it can model is next Sunday's window end.
            Rule rule = new RuleBuilder().noParking().onDays(SUNDAY)
                    .duringWindow(LocalTime.of(0, 30), LocalTime.of(1, 30)).build();
            ZonedDateTime at = Instant.parse("2026-11-01T09:00:00Z").atZone(LA); // 01:00 PST
            RuleActivation activation = evaluator.evaluate(rule, at);
            assertThat(activation.active()).isTrue();
            assertThat(activation.nextBoundary()).isPresent();
            assertThat(activation.nextBoundary().orElseThrow()).isAfter(at.toInstant());
            assertThat(activation.nextBoundary()).contains(Instant.parse("2026-11-08T09:30:00Z")); // next Sun 01:30 PST
        }
    }

    // -- holiday suspension -------------------------------------------------

    @Nested
    class HolidaySuspension {

        @Test
        void fixedHolidaySuspendsRuleForTheDay() {
            Rule rule = new RuleBuilder().noParking().onDays(FRIDAY)
                    .duringWindow(LocalTime.of(8, 0), LocalTime.of(18, 0))
                    .suspendedOnHolidays().build();
            // 2025-07-04 is a Friday and Independence Day
            RuleActivation activation = withHolidays.evaluate(rule, la(2025, 7, 4, 10, 0));
            assertThat(activation.active()).isFalse();
            assertThat(activation.reason()).containsIgnoringCase("holiday");
            assertThat(activation.nextBoundary()).contains(Instant.parse("2025-07-11T15:00:00Z")); // next Fri 08:00 PDT
        }

        @Test
        void ruleWithoutSuspensionStillAppliesOnHoliday() {
            Rule rule = new RuleBuilder().noParking().onDays(FRIDAY)
                    .duringWindow(LocalTime.of(8, 0), LocalTime.of(18, 0)).build();
            RuleActivation activation = withHolidays.evaluate(rule, la(2025, 7, 4, 10, 0));
            assertThat(activation.active()).isTrue();
        }

        @Test
        void floatingHolidaySuspendsRule() {
            Rule rule = new RuleBuilder().noParking().onDays(THURSDAY)
                    .duringWindow(LocalTime.of(8, 0), LocalTime.of(18, 0))
                    .suspendedOnHolidays().build();
            // Thanksgiving 2026-11-26; next Thursday 2026-12-03 08:00 PST
            RuleActivation activation = withHolidays.evaluate(rule, la(2026, 11, 26, 10, 0));
            assertThat(activation.active()).isFalse();
            assertThat(activation.nextBoundary()).contains(Instant.parse("2026-12-03T16:00:00Z"));
        }

        @Test
        void weekendObservedHolidaySuspendsRuleOnObservedFriday() {
            Rule rule = new RuleBuilder().noParking().onDays(FRIDAY)
                    .duringWindow(LocalTime.of(8, 0), LocalTime.of(18, 0))
                    .suspendedOnHolidays().build();
            // 2026-07-04 is a Saturday, observed Friday 2026-07-03
            RuleActivation activation = withHolidays.evaluate(rule, la(2026, 7, 3, 10, 0));
            assertThat(activation.active()).isFalse();
        }
    }

    // -- date-range effectivity ----------------------------------------------

    @Nested
    class DateRangeEffectivity {

        private final Rule seasonal = new RuleBuilder().noParking().onAnyDay()
                .duringWindow(LocalTime.of(8, 0), LocalTime.of(18, 0))
                .effectiveBetween(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31)).build();

        @Test
        void inactiveBeforeEffectiveDateBoundaryIsStartOfEffectiveDate() {
            RuleActivation activation = evaluator.evaluate(seasonal, la(2026, 7, 15, 10, 0));
            assertThat(activation.active()).isFalse();
            assertThat(activation.reason()).containsIgnoringCase("effective");
            assertThat(activation.nextBoundary()).contains(Instant.parse("2026-09-01T07:00:00Z")); // Sep 1 00:00 PDT
        }

        @Test
        void inactiveAfterSunsetDateWithNoFurtherBoundary() {
            RuleActivation activation = evaluator.evaluate(seasonal, la(2027, 1, 15, 10, 0));
            assertThat(activation.active()).isFalse();
            assertThat(activation.nextBoundary()).isEmpty();
        }

        @Test
        void activeWithinDateRange() {
            RuleActivation activation = evaluator.evaluate(seasonal, la(2026, 10, 5, 10, 0));
            assertThat(activation.active()).isTrue();
            assertThat(activation.nextBoundary()).contains(Instant.parse("2026-10-06T01:00:00Z")); // 18:00 PDT
        }

        @Test
        void effectiveDateBeyondLookaheadStillReportsEffectiveStartAsBoundary() {
            // A >366-day forward scan is never attempted: the effectivity check
            // short-circuits with the effective date itself.
            Rule farFuture = new RuleBuilder().noParking().onAnyDay()
                    .duringWindow(LocalTime.of(8, 0), LocalTime.of(18, 0))
                    .effectiveFrom(LocalDate.of(2028, 7, 15)).build();
            RuleActivation activation = evaluator.evaluate(farFuture, la(2026, 7, 15, 10, 0));
            assertThat(activation.active()).isFalse();
            assertThat(activation.nextBoundary()).contains(Instant.parse("2028-07-15T07:00:00Z"));
        }
    }

    // -- degenerate & structural cases ----------------------------------------

    @Test
    void emptyDayPatternNeverMatchesReturnsNoBoundaryWithoutHanging() {
        Rule rule = new RuleBuilder().noParking().onNoDays().anyTime().build();
        RuleActivation activation = evaluator.evaluate(rule, la(2026, 7, 15, 10, 0));
        assertThat(activation.active()).isFalse();
        assertThat(activation.nextBoundary()).isEmpty();
    }

    @Test
    void zeroDurationWindowIsNeverActiveAndYieldsNoBoundary() {
        // start == end should be caught by Stage B validation; the evaluator
        // must still degrade gracefully rather than loop or divide the day.
        Rule rule = new RuleBuilder().noParking().onAnyDay()
                .duringWindow(LocalTime.of(9, 0), LocalTime.of(9, 0)).build();
        RuleActivation activation = evaluator.evaluate(rule, la(2026, 7, 15, 9, 0));
        assertThat(activation.active()).isFalse();
        assertThat(activation.nextBoundary()).isEmpty();
    }

    @Test
    void multipleWindowsRuleActiveInAnyWindowAndBoundaryConsidersAll() {
        Rule rule = new RuleBuilder().noParking().onAnyDay()
                .duringWindow(LocalTime.of(8, 0), LocalTime.of(12, 0))
                .duringWindow(LocalTime.of(14, 0), LocalTime.of(18, 0)).build();

        RuleActivation between = evaluator.evaluate(rule, la(2026, 7, 15, 13, 0));
        assertThat(between.active()).isFalse();
        assertThat(between.nextBoundary()).contains(Instant.parse("2026-07-15T21:00:00Z")); // 14:00 PDT

        RuleActivation inFirst = evaluator.evaluate(rule, la(2026, 7, 15, 9, 0));
        assertThat(inFirst.active()).isTrue();
        assertThat(inFirst.nextBoundary()).contains(Instant.parse("2026-07-15T19:00:00Z")); // 12:00 PDT

        RuleActivation inSecond = evaluator.evaluate(rule, la(2026, 7, 15, 15, 0));
        assertThat(inSecond.active()).isTrue();
        assertThat(inSecond.nextBoundary()).contains(Instant.parse("2026-07-16T01:00:00Z")); // 18:00 PDT
    }

    @Test
    void sameInstantDifferentZonesCanDisagree() {
        Rule rule = new RuleBuilder().noParking()
                .onDays(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
                .duringWindow(LocalTime.of(8, 0), LocalTime.of(18, 0)).build();
        Instant instant = Instant.parse("2026-07-15T01:00:00Z");
        // LA: Tue 2026-07-14 18:00 PDT — exactly at the exclusive window end
        assertThat(evaluator.evaluate(rule, instant.atZone(LA)).active()).isFalse();
        // Tokyo: Wed 2026-07-15 10:00 — mid-window
        assertThat(evaluator.evaluate(rule, instant.atZone(ZoneId.of("Asia/Tokyo"))).active()).isTrue();
    }
}
