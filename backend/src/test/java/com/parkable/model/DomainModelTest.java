package com.parkable.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
}
