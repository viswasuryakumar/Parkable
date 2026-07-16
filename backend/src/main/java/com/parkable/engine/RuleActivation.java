package com.parkable.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Single-rule answer: is the rule active at the evaluated instant, and when
 * does that answer next change (empty = no modelled boundary).
 */
public record RuleActivation(
        boolean active,
        Optional<Instant> nextBoundary,
        String reason
) {
    public RuleActivation {
        Objects.requireNonNull(nextBoundary, "nextBoundary");
        Objects.requireNonNull(reason, "reason");
    }
}
