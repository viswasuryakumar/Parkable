package com.parkable.model;

import java.util.Objects;

/**
 * Permit required while active. The engine cannot verify permit possession,
 * so an active permit rule yields DEPENDS rather than a hard verdict.
 */
public record PermitRule(RuleMetadata metadata, String permitZone) implements Rule {
    public PermitRule {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(permitZone, "permitZone");
    }
}
