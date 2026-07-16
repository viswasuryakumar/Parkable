package com.parkable.model;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class TimeWindowTest {

    private final TimeWindow sameDay = new TimeWindow(LocalTime.of(8, 0), LocalTime.of(18, 0));
    private final TimeWindow overnight = new TimeWindow(LocalTime.of(23, 0), LocalTime.of(2, 0));

    @Test
    void sameDayWindowContainsTimeInside() {
        assertThat(sameDay.contains(LocalTime.of(12, 30))).isTrue();
    }

    @Test
    void sameDayWindowExcludesBeforeStartAndAfterEnd() {
        assertThat(sameDay.contains(LocalTime.of(7, 59))).isFalse();
        assertThat(sameDay.contains(LocalTime.of(18, 1))).isFalse();
    }

    @Test
    void sameDayWindowIsStartInclusiveEndExclusive() {
        assertThat(sameDay.contains(LocalTime.of(8, 0))).isTrue();
        assertThat(sameDay.contains(LocalTime.of(18, 0))).isFalse();
    }

    @Test
    void midnightCrossingWindowContainsLateEveningAndEarlyMorning() {
        assertThat(overnight.contains(LocalTime.of(23, 30))).isTrue();
        assertThat(overnight.contains(LocalTime.of(1, 0))).isTrue();
    }

    @Test
    void midnightCrossingWindowExcludesDaytime() {
        assertThat(overnight.contains(LocalTime.of(3, 0))).isFalse();
        assertThat(overnight.contains(LocalTime.of(22, 59))).isFalse();
    }

    @Test
    void midnightCrossingWindowIsStartInclusiveEndExclusive() {
        assertThat(overnight.contains(LocalTime.of(23, 0))).isTrue();
        assertThat(overnight.contains(LocalTime.of(2, 0))).isFalse();
    }

    @Test
    void crossesMidnightDetection() {
        assertThat(sameDay.crossesMidnight()).isFalse();
        assertThat(overnight.crossesMidnight()).isTrue();
    }

    @Test
    void zeroDurationWindowContainsNothing() {
        TimeWindow degenerate = new TimeWindow(LocalTime.of(9, 0), LocalTime.of(9, 0));
        assertThat(degenerate.crossesMidnight()).isFalse();
        assertThat(degenerate.contains(LocalTime.of(9, 0))).isFalse();
    }
}
