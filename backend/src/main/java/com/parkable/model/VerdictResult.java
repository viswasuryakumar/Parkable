package com.parkable.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Engine output: the verdict, which rule triggered it (if any), the earliest
 * instant at which the answer could change (empty = no known boundary), and a
 * step-by-step trace for audit.
 */
public record VerdictResult(
        Verdict verdict,
        Optional<RuleMatch> triggeringRule,
        Optional<Instant> validUntil,
        List<String> trace
) {
    public VerdictResult {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(triggeringRule, "triggeringRule");
        Objects.requireNonNull(validUntil, "validUntil");
        trace = List.copyOf(trace);
    }
}
