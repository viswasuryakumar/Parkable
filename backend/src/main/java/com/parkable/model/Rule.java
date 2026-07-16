package com.parkable.model;

/**
 * A single parking regulation. Sealed so the engine's most-restrictive-wins
 * ranking can switch exhaustively over rule types.
 *
 * <p>schema.md's {@code street_cleaning} maps to {@link NoParkingRule} (same
 * semantics, different day pattern); {@code color_curb} and {@code restricted}
 * will be mapped by Stage B's RuleFactory.
 */
public sealed interface Rule permits NoParkingRule, TimeLimitRule, PermitRule {
    RuleMetadata metadata();
}
