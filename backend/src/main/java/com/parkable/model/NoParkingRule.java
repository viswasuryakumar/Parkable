package com.parkable.model;

import java.util.Objects;

/** Parking prohibited outright while active. */
public record NoParkingRule(RuleMetadata metadata) implements Rule {
    public NoParkingRule {
        Objects.requireNonNull(metadata, "metadata");
    }
}
