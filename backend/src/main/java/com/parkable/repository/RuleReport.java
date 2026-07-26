package com.parkable.repository;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A "this looks wrong/outdated" flag against a rule. Purely a record for
 * later human review - saving one never automatically changes or removes
 * the rule it points at (a bad-faith report must not be able to take down a
 * real regulation).
 */
public record RuleReport(UUID id, String ruleId, String reason, String deviceId, Instant reportedAt) {
    public RuleReport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reportedAt, "reportedAt");
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
    }
}
