package com.parkable.model;

import java.util.Objects;

/** The rule that decided the verdict, with a human-readable reason. */
public record RuleMatch(Rule rule, String reason) {
    public RuleMatch {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(reason, "reason");
    }
}
