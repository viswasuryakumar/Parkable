package com.parkable.lambda.port;

import com.parkable.model.Rule;

import java.util.Objects;

/**
 * A rule as returned from storage: the domain rule plus the provenance tags
 * every stored rule must carry (architecture rule #4).
 */
public record StoredRule(Rule rule, String source, String parserVersion) {
    public StoredRule {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(parserVersion, "parserVersion");
    }
}
