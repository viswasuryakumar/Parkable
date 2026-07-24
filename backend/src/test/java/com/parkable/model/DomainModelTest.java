package com.parkable.model;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainModelTest {

    @Test
    void unspecifiedDirectionalModifierDefaults() {
        DirectionalModifier modifier = DirectionalModifier.unspecified();
        assertThat(modifier.side()).isEqualTo(Side.NOT_SPECIFIED);
        assertThat(modifier.arrow()).isEqualTo(ArrowDirection.NONE);
    }

    @Test
    void dateRangeIncludesBothBoundsInclusive() {
        DateRange range = DateRange.between(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31));
        assertThat(range.includes(LocalDate.of(2026, 9, 1))).isTrue();
        assertThat(range.includes(LocalDate.of(2026, 12, 31))).isTrue();
        assertThat(range.includes(LocalDate.of(2026, 8, 31))).isFalse();
        assertThat(range.includes(LocalDate.of(2027, 1, 1))).isFalse();
    }

    @Test
    void dateRangeRejectsSunsetBeforeEffective() {
        assertThatThrownBy(() -> DateRange.between(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openEndedDateRangeNeverExpires() {
        DateRange range = DateRange.from(LocalDate.of(2026, 9, 1));
        assertThat(range.includes(LocalDate.of(2099, 1, 1))).isTrue();
    }

    @Test
    void ruleMetadataDefensivelyCopiesTimeWindows() {
        List<TimeWindow> windows = new ArrayList<>();
        windows.add(new TimeWindow(LocalTime.of(8, 0), LocalTime.of(18, 0)));
        RuleMetadata metadata = new RuleMetadata("r1", "test", SpecificDays.anyDay(),
                windows, Optional.empty(), HolidayPolicy.notSuspended(), DirectionalModifier.unspecified());
        windows.clear();
        assertThat(metadata.timeWindows()).hasSize(1);
        assertThat(metadata.isAnyTime()).isFalse();
    }

    private static RuleMetadata metadata(String id, String description, java.util.Set<DayOfWeek> days,
                                          List<TimeWindow> windows) {
        return new RuleMetadata(id, description, new SpecificDays(days), windows,
                Optional.empty(), HolidayPolicy.notSuspended(), DirectionalModifier.unspecified());
    }

    @Test
    void sameRegulationIgnoresRuleIdAndDescriptionWording() {
        List<TimeWindow> windows = List.of(new TimeWindow(LocalTime.of(8, 0), LocalTime.of(17, 30)));
        Rule first = new TimeLimitRule(
                metadata("scan-1:rule_1", "2 hour parking", EnumSet.allOf(DayOfWeek.class), windows),
                Duration.ofMinutes(120));
        Rule second = new TimeLimitRule(
                metadata("scan-2:r1", "2 Hour Parking Allowed 8:30-5:30", EnumSet.allOf(DayOfWeek.class), windows),
                Duration.ofMinutes(120));

        assertThat(first.describesSameRegulation(second)).isTrue();
        assertThat(second.describesSameRegulation(first)).isTrue();
    }

    @Test
    void differentDayPatternIsNotTheSameRegulation() {
        List<TimeWindow> windows = List.of(new TimeWindow(LocalTime.of(2, 0), LocalTime.of(6, 0)));
        Rule monWedFriSat = new NoParkingRule(metadata("r1", "Street cleaning",
                Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), windows));
        Rule everyDay = new NoParkingRule(metadata("r2", "Street cleaning", EnumSet.allOf(DayOfWeek.class), windows));

        assertThat(monWedFriSat.describesSameRegulation(everyDay)).isFalse();
    }

    @Test
    void differentTimeWindowIsNotTheSameRegulation() {
        Rule fourToSix = new NoParkingRule(metadata("r1", "No stopping", EnumSet.allOf(DayOfWeek.class),
                List.of(new TimeWindow(LocalTime.of(16, 0), LocalTime.of(18, 0)))));
        Rule fourToSixAm = new NoParkingRule(metadata("r2", "No stopping", EnumSet.allOf(DayOfWeek.class),
                List.of(new TimeWindow(LocalTime.of(4, 0), LocalTime.of(6, 0)))));

        assertThat(fourToSix.describesSameRegulation(fourToSixAm)).isFalse();
    }

    @Test
    void differentRuleTypeIsNotTheSameRegulationEvenWithIdenticalScheduleFields() {
        List<TimeWindow> windows = List.of(new TimeWindow(LocalTime.of(8, 0), LocalTime.of(18, 0)));
        Rule noParking = new NoParkingRule(metadata("r1", "d", EnumSet.allOf(DayOfWeek.class), windows));
        Rule timeLimit = new TimeLimitRule(
                metadata("r2", "d", EnumSet.allOf(DayOfWeek.class), windows), Duration.ofMinutes(120));

        assertThat(noParking.describesSameRegulation(timeLimit)).isFalse();
    }

    @Test
    void differentDurationIsNotTheSameRegulation() {
        List<TimeWindow> windows = List.of(new TimeWindow(LocalTime.of(8, 0), LocalTime.of(18, 0)));
        Rule twoHour = new TimeLimitRule(
                metadata("r1", "d", EnumSet.allOf(DayOfWeek.class), windows), Duration.ofMinutes(120));
        Rule fourHour = new TimeLimitRule(
                metadata("r2", "d", EnumSet.allOf(DayOfWeek.class), windows), Duration.ofMinutes(240));

        assertThat(twoHour.describesSameRegulation(fourHour)).isFalse();
    }

    @Test
    void differentPermitZoneIsNotTheSameRegulation() {
        List<TimeWindow> windows = List.of();
        Rule zoneA = new PermitRule(metadata("r1", "d", EnumSet.allOf(DayOfWeek.class), windows), "A");
        Rule zoneB = new PermitRule(metadata("r2", "d", EnumSet.allOf(DayOfWeek.class), windows), "B");

        assertThat(zoneA.describesSameRegulation(zoneB)).isFalse();
    }
}
