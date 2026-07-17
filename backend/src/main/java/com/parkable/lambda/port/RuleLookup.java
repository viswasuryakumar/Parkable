package com.parkable.lambda.port;

import java.util.List;

/**
 * Geo-query seam the handlers depend on. Phase 2's Postgres implementation
 * (Codex X4) answers this with ST_DWithin against the PostGIS rules table,
 * applying the parser_version cache-validity filter (plan decision D5);
 * tests answer it from memory.
 */
public interface RuleLookup {

    List<StoredRule> findWithin(double latitude, double longitude, double radiusMeters);
}
