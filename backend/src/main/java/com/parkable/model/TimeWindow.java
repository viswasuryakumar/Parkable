package com.parkable.model;

import java.time.LocalTime;
import java.util.Objects;

/**
 * A daily time window, start-inclusive and end-exclusive.
 *
 * <p>Owns the midnight-crossing logic: a window whose end is numerically before
 * its start (e.g. 23:00-02:00) wraps past midnight into the following day.
 */
public record TimeWindow(LocalTime start, LocalTime end) {

    public TimeWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
    }

    public boolean contains(LocalTime t) {
        // 23:00-02:00 means "t >= 23:00 OR t < 02:00"; the OR (not AND) is what
        // makes the wrap work, because no single LocalTime satisfies both.
        return crossesMidnight()
                ? !t.isBefore(start) || t.isBefore(end)
                : !t.isBefore(start) && t.isBefore(end);
    }

    public boolean crossesMidnight() {
        return end.isBefore(start);
    }
}
