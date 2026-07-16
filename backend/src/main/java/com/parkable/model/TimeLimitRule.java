package com.parkable.model;

import java.time.Duration;
import java.util.Objects;

/** Parking allowed for at most {@code limit} while active. */
public record TimeLimitRule(RuleMetadata metadata, Duration limit) implements Rule {
    public TimeLimitRule {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(limit, "limit");
        if (limit.isNegative() || limit.isZero()) {
            throw new IllegalArgumentException("time limit must be positive: " + limit);
        }
    }
}
