package com.parkable.model;

import java.util.Objects;

/**
 * A regulation that appears on/near a sign but does not, by itself, restrict
 * whether a single vehicle may legally occupy the curb space (e.g. "No
 * Double Parking" — prohibits a second row, says nothing about the space
 * itself). Never wins {@code RulesEngine}'s most-restrictive-wins ranking
 * and always resolves to PARKABLE if it's the only active rule, so it can't
 * turn a legal spot into a false NOT_PARKABLE the way flattening it into
 * {@link NoParkingRule} would.
 */
public record InformationalRule(RuleMetadata metadata) implements Rule {
    public InformationalRule {
        Objects.requireNonNull(metadata, "metadata");
    }
}
