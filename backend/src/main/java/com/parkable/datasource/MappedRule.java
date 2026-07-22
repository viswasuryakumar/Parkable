package com.parkable.datasource;

import com.parkable.model.Rule;

import java.util.Objects;

/** A normalized rule paired with the WGS84 point at which it was published. */
public record MappedRule(Rule rule, double latitude, double longitude) {
    public MappedRule {
        Objects.requireNonNull(rule, "rule");
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude must be a finite WGS84 latitude");
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude must be a finite WGS84 longitude");
        }
    }
}
