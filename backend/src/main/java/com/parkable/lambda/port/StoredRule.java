package com.parkable.lambda.port;

import com.parkable.model.Rule;

import java.util.Objects;

/**
 * A rule as returned from storage: the domain rule, the provenance tags every
 * stored rule must carry (architecture rule #4), and the WGS84 point where
 * the rule physically stands — every writer knows it (scan GPS, gov record
 * geometry), and readers need it to tell the user WHERE a rule applies, not
 * just that one exists somewhere within the query radius.
 */
public record StoredRule(Rule rule, String source, String parserVersion, double latitude, double longitude) {
    public StoredRule {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(parserVersion, "parserVersion");
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
    }
}
