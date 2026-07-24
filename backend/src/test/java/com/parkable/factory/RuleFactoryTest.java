package com.parkable.factory;

import com.parkable.extraction.dto.DayPatternDto;
import com.parkable.extraction.dto.DirectionDto;
import com.parkable.extraction.dto.ExceptionDto;
import com.parkable.extraction.dto.ExtractionEnvelope;
import com.parkable.extraction.dto.RestrictionDto;
import com.parkable.extraction.dto.RuleDto;
import com.parkable.extraction.dto.TimeWindowDto;
import com.parkable.model.ArrowDirection;
import com.parkable.model.DateRange;
import com.parkable.model.InformationalRule;
import com.parkable.model.NoParkingRule;
import com.parkable.model.PermitRule;
import com.parkable.model.Rule;
import com.parkable.model.Side;
import com.parkable.model.SpecificDays;
import com.parkable.model.TimeLimitRule;
import com.parkable.model.TimeWindow;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleFactoryTest {

    private static final DayPatternDto WEEKDAYS = new DayPatternDto(
            "specific_days", List.of("MON", "TUE", "WED", "THU", "FRI"), null, null, null, null, null);

    private static RuleDto ruleDto(String type, RestrictionDto restriction) {
        return new RuleDto("r1", null, type, "test rule", null, restriction,
                List.of(new TimeWindowDto("08:00", "18:00", false, false)),
                WEEKDAYS, null, null, null);
    }

    @Test
    void mapsNoParkingRule() {
        Rule rule = RuleFactory.from(ruleDto("no_parking", null));

        assertThat(rule).isInstanceOf(NoParkingRule.class);
        assertThat(rule.metadata().ruleId()).isEqualTo("r1");
        assertThat(rule.metadata().timeWindows())
                .containsExactly(new TimeWindow(LocalTime.of(8, 0), LocalTime.of(18, 0)));
        assertThat(rule.metadata().dayPattern())
                .isEqualTo(new SpecificDays(java.util.Set.of(
                        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)));
    }

    @Test
    void streetCleaningAndRestrictedMapToNoParking() {
        assertThat(RuleFactory.from(ruleDto("street_cleaning", null))).isInstanceOf(NoParkingRule.class);
        assertThat(RuleFactory.from(ruleDto("restricted", null))).isInstanceOf(NoParkingRule.class);
    }

    @Test
    void mapsTimeLimitRule() {
        Rule rule = RuleFactory.from(ruleDto("time_limit",
                new RestrictionDto(120, null, null, null, null)));

        assertThat(rule).isInstanceOf(TimeLimitRule.class);
        assertThat(((TimeLimitRule) rule).limit()).isEqualTo(Duration.ofMinutes(120));
    }

    @Test
    void mapsPermitRule() {
        Rule rule = RuleFactory.from(ruleDto("permit_required",
                new RestrictionDto(null, "residential", null, null, null)));

        assertThat(rule).isInstanceOf(PermitRule.class);
        assertThat(((PermitRule) rule).permitZone()).isEqualTo("residential");
    }

    @Test
    void mapsDoubleParkingProhibitedToInformationalRuleNotNoParking() {
        // The live bug this guards: a "No Double Parking" sign restricts a
        // second row, not the curb space - it must never become a
        // NoParkingRule, which the engine treats as always-NOT_PARKABLE.
        Rule rule = RuleFactory.from(ruleDto("double_parking_prohibited", null));

        assertThat(rule).isInstanceOf(InformationalRule.class);
        assertThat(rule.metadata().ruleId()).isEqualTo("r1");
    }

    @Test
    void toDtoRoundTripsInformationalRule() {
        Rule original = RuleFactory.from(ruleDto("double_parking_prohibited", null));

        Rule roundTripped = RuleFactory.from(RuleFactory.toDto(original));

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void mapsColorCurbsByColor() {
        assertThat(RuleFactory.from(ruleDto("color_curb",
                new RestrictionDto(null, null, "red", null, null))))
                .isInstanceOf(NoParkingRule.class);
        assertThat(RuleFactory.from(ruleDto("color_curb",
                new RestrictionDto(null, null, "blue", null, null))))
                .isInstanceOf(PermitRule.class);

        Rule green = RuleFactory.from(ruleDto("color_curb",
                new RestrictionDto(30, null, "green", null, null)));
        assertThat(green).isInstanceOf(TimeLimitRule.class);
        assertThat(((TimeLimitRule) green).limit()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void unknownTypeFailsFast() {
        assertThatThrownBy(() -> RuleFactory.from(ruleDto("valet_only", null)))
                .isInstanceOf(UnsupportedSignTypeException.class)
                .hasMessageContaining("valet_only");
    }

    @Test
    void anyDayAndAnyMarkerBothMeanAllSevenDays() {
        RuleDto anyDay = new RuleDto("r1", null, "no_parking", "d", null, null, null,
                new DayPatternDto("any_day", null, null, null, null, null, null), null, null, null);
        RuleDto anyMarker = new RuleDto("r2", null, "no_parking", "d", null, null, null,
                new DayPatternDto("specific_days", List.of("ANY"), null, null, null, null, null),
                null, null, null);

        assertThat(RuleFactory.from(anyDay).metadata().dayPattern()).isEqualTo(SpecificDays.anyDay());
        assertThat(RuleFactory.from(anyMarker).metadata().dayPattern()).isEqualTo(SpecificDays.anyDay());
    }

    @Test
    void nthWeekdayPatternMaps() {
        RuleDto dto = new RuleDto("r1", null, "street_cleaning", "d", null, null, null,
                new DayPatternDto("nth_weekday_of_month", null, "TUE", List.of(1, 3), null, null, null),
                null, null, null);

        Rule rule = RuleFactory.from(dto);
        // 2026-07-07 is the 1st Tuesday of July; 2026-07-14 the 2nd.
        assertThat(rule.metadata().dayPattern().matches(LocalDate.of(2026, 7, 7))).isTrue();
        assertThat(rule.metadata().dayPattern().matches(LocalDate.of(2026, 7, 14))).isFalse();
    }

    @Test
    void dateRangePatternBecomesAnyDayWithBounds() {
        RuleDto dto = new RuleDto("r1", null, "no_parking", "d", null, null, null,
                new DayPatternDto("date_range", null, null, null, "2026-06-01", "2026-08-31", null),
                null, null, null);

        Rule rule = RuleFactory.from(dto);
        assertThat(rule.metadata().dayPattern()).isEqualTo(SpecificDays.anyDay());
        assertThat(rule.metadata().dateRange())
                .contains(DateRange.between(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 31)));
    }

    @Test
    void yearRoundSuppressesDateBounds() {
        RuleDto dto = new RuleDto("r1", null, "no_parking", "d", null, null, null,
                new DayPatternDto("specific_days", List.of("MON"), null, null, "2026-06-01", null, true),
                null, null, null);

        assertThat(RuleFactory.from(dto).metadata().dateRange()).isEmpty();
    }

    @Test
    void allDayWindowMeansAnyTime() {
        RuleDto dto = new RuleDto("r1", null, "no_parking", "d", null, null,
                List.of(new TimeWindowDto(null, null, null, true)),
                WEEKDAYS, null, null, null);

        assertThat(RuleFactory.from(dto).metadata().isAnyTime()).isTrue();
    }

    @Test
    void holidaySuspensionExceptionMapsToPolicy() {
        RuleDto suspended = new RuleDto("r1", null, "no_parking", "d", null, null, null, WEEKDAYS,
                List.of(new ExceptionDto("holiday_suspension", "Except holidays", null, null, null)),
                null, null);
        RuleDto otherException = new RuleDto("r2", null, "no_parking", "d", null, null, null, WEEKDAYS,
                List.of(new ExceptionDto("snow_emergency", "Snow route", null, null, null)),
                null, null);

        assertThat(RuleFactory.from(suspended).metadata().holidayPolicy().suspendedOnHolidays()).isTrue();
        assertThat(RuleFactory.from(otherException).metadata().holidayPolicy().suspendedOnHolidays()).isFalse();
    }

    @Test
    void directionMapsAndDefaultsToUnspecified() {
        RuleDto sided = new RuleDto("r1", null, "no_parking", "d", null, null, null, WEEKDAYS, null,
                new DirectionDto("left", null, null, "north"), null);
        RuleDto noDirection = ruleDto("no_parking", null);

        assertThat(RuleFactory.from(sided).metadata().direction().side()).isEqualTo(Side.LEFT);
        assertThat(RuleFactory.from(sided).metadata().direction().arrow()).isEqualTo(ArrowDirection.NORTH);
        assertThat(RuleFactory.from(noDirection).metadata().direction().side()).isEqualTo(Side.NOT_SPECIFIED);
    }

    @Test
    void descriptionFallsBackToOriginalThenType() {
        RuleDto original = new RuleDto("r1", null, "no_parking", null, "RAW SIGN TEXT",
                null, null, WEEKDAYS, null, null, null);
        RuleDto bare = new RuleDto("r2", null, "no_parking", null, null,
                null, null, WEEKDAYS, null, null, null);

        assertThat(RuleFactory.from(original).metadata().description()).isEqualTo("RAW SIGN TEXT");
        assertThat(RuleFactory.from(bare).metadata().description()).isEqualTo("no_parking");
    }

    @Test
    void toDtoRoundTripsNoParkingRuleWithSpecificDaysAndTimeWindow() {
        Rule original = RuleFactory.from(ruleDto("no_parking", null));

        Rule roundTripped = RuleFactory.from(RuleFactory.toDto(original));

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void toDtoRoundTripsTimeLimitRule() {
        Rule original = RuleFactory.from(ruleDto("time_limit", new RestrictionDto(120, null, null, null, null)));

        Rule roundTripped = RuleFactory.from(RuleFactory.toDto(original));

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void toDtoRoundTripsPermitRule() {
        Rule original = RuleFactory.from(ruleDto("permit_required", new RestrictionDto(null, "residential", null, null, null)));

        Rule roundTripped = RuleFactory.from(RuleFactory.toDto(original));

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void toDtoRoundTripsAnyDayPattern() {
        RuleDto dto = new RuleDto("r1", null, "no_parking", "d", null, null, null,
                new DayPatternDto("any_day", null, null, null, null, null, null), null, null, null);
        Rule original = RuleFactory.from(dto);

        Rule roundTripped = RuleFactory.from(RuleFactory.toDto(original));

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void toDtoRoundTripsDateRangeBounds() {
        RuleDto dto = new RuleDto("r1", null, "no_parking", "d", null, null, null,
                new DayPatternDto("date_range", null, null, null, "2026-06-01", "2026-08-31", null),
                null, null, null);
        Rule original = RuleFactory.from(dto);

        Rule roundTripped = RuleFactory.from(RuleFactory.toDto(original));

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void toDtoRoundTripsNthWeekdayPattern() {
        RuleDto dto = new RuleDto("r1", null, "street_cleaning", "d", null, null, null,
                new DayPatternDto("nth_weekday_of_month", null, "TUE", List.of(1, 3), null, null, null),
                null, null, null);
        Rule original = RuleFactory.from(dto);

        Rule roundTripped = RuleFactory.from(RuleFactory.toDto(original));

        assertThat(roundTripped.metadata().dayPattern().matches(LocalDate.of(2026, 7, 7))).isTrue();
        assertThat(roundTripped.metadata().dayPattern().matches(LocalDate.of(2026, 7, 14))).isFalse();
        assertThat(roundTripped.metadata().dayPattern().matches(LocalDate.of(2026, 7, 21))).isTrue();
    }

    @Test
    void toDtoRoundTripsHolidaySuspension() {
        RuleDto suspended = new RuleDto("r1", null, "no_parking", "d", null, null, null, WEEKDAYS,
                List.of(new ExceptionDto("holiday_suspension", "Except holidays", null, null, null)), null, null);
        Rule original = RuleFactory.from(suspended);

        Rule roundTripped = RuleFactory.from(RuleFactory.toDto(original));

        assertThat(roundTripped.metadata().holidayPolicy().suspendedOnHolidays()).isTrue();
    }

    @Test
    void toDtoRoundTripsDirection() {
        RuleDto sided = new RuleDto("r1", null, "no_parking", "d", null, null, null, WEEKDAYS, null,
                new DirectionDto("left", null, null, "north"), null);
        Rule original = RuleFactory.from(sided);

        Rule roundTripped = RuleFactory.from(RuleFactory.toDto(original));

        assertThat(roundTripped.metadata().direction()).isEqualTo(original.metadata().direction());
    }

    @Test
    void toDtoOmitsDirectionWhenUnspecified() {
        Rule original = RuleFactory.from(ruleDto("no_parking", null));

        assertThat(RuleFactory.toDto(original).direction()).isNull();
    }

    @Test
    void fromEnvelopeSkipsDeprecatedAndSupersededRules() {
        RuleDto active = ruleDto("no_parking", null);
        RuleDto deprecated = new RuleDto("r-old", null, "no_parking", "d", null, null, null,
                WEEKDAYS, null, null, "deprecated");
        RuleDto superseded = new RuleDto("r-superseded", null, "no_parking", "d", null, null, null,
                WEEKDAYS, null, null, "superseded");
        ExtractionEnvelope envelope = new ExtractionEnvelope(
                "e1", "camera_scan", null, null, "test-v1", "2026-07-14T18:04:00Z", null,
                0.9, null, null, null, List.of(active, deprecated, superseded));

        List<Rule> rules = RuleFactory.fromEnvelope(envelope);

        assertThat(rules).hasSize(1);
        assertThat(rules.getFirst().metadata().ruleId()).isEqualTo("r1");
    }
}
