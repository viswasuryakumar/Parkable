package com.parkable.model;

import java.util.Objects;

/**
 * Side-of-street / arrow qualifiers on a rule.
 *
 * <p>// Stage B: schema.md's direction.cardinal and direction.house_numbers
 * fields will be mapped onto this type by RuleFactory when extraction lands.
 */
public record DirectionalModifier(Side side, ArrowDirection arrow) {

    public DirectionalModifier {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(arrow, "arrow");
    }

    public static DirectionalModifier unspecified() {
        return new DirectionalModifier(Side.NOT_SPECIFIED, ArrowDirection.NONE);
    }
}
