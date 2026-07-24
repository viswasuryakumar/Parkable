package com.parkable.lambda.port;

import com.parkable.model.Rule;

import java.util.Objects;

/**
 * A rule as returned from storage: the domain rule, the provenance tags every
 * stored rule must carry (architecture rule #4), the WGS84 point where the
 * rule physically stands (every writer knows it — scan GPS, gov record
 * geometry), and the id of the extraction/import event that produced it.
 *
 * <p>{@code scanId} matters because rules sharing a location aren't
 * necessarily one sign: distinct signs standing within a few metres of each
 * other are common, and a client grouping purely by (source, point) can't
 * tell "3 rules from one photo" apart from "rules from 2 different photos
 * that happen to share a spot" without it — found live when a rescan at the
 * same coordinates needed to coexist with, not replace, a different sign.
 */
public record StoredRule(Rule rule, String source, String parserVersion, double latitude, double longitude,
                          String scanId) {
    public StoredRule {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(parserVersion, "parserVersion");
        Objects.requireNonNull(scanId, "scanId");
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
    }
}
