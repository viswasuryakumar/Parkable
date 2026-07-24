package com.parkable.model;

/**
 * A single parking regulation. Sealed so the engine's most-restrictive-wins
 * ranking can switch exhaustively over rule types.
 *
 * <p>schema.md's {@code street_cleaning} maps to {@link NoParkingRule} (same
 * semantics, different day pattern); {@code color_curb} and {@code restricted}
 * are mapped by {@code RuleFactory} too. {@code double_parking_prohibited}
 * maps to {@link InformationalRule} — a distinct regulation from a genuine
 * no-parking prohibition, so it must not inherit NoParkingRule's
 * always-NOT_PARKABLE verdict.
 */
public sealed interface Rule permits NoParkingRule, TimeLimitRule, PermitRule, InformationalRule {
    RuleMetadata metadata();

    /**
     * Same regulation in substance, ignoring identity fields that always
     * differ between independent reads of the same sign (ruleId, free-text
     * description/holiday/direction wording). Used to decide whether a fresh
     * camera scan is honestly a re-read of an EXISTING stored rule (safe to
     * supersede) versus a genuinely different regulation that happens to be
     * nearby (must NOT be deleted just because two signs share a location -
     * a live rescan wiped out a real, different 3-panel sign this way when
     * superseding was based on proximity alone).
     */
    default boolean describesSameRegulation(Rule other) {
        if (!getClass().equals(other.getClass())) {
            return false;
        }
        if (!metadata().dayPattern().equals(other.metadata().dayPattern())) {
            return false;
        }
        if (!metadata().timeWindows().equals(other.metadata().timeWindows())) {
            return false;
        }
        return switch (this) {
            case TimeLimitRule mine -> mine.limit().equals(((TimeLimitRule) other).limit());
            case PermitRule mine -> mine.permitZone().equals(((PermitRule) other).permitZone());
            case NoParkingRule ignored -> true;
            case InformationalRule ignored -> true;
        };
    }
}
