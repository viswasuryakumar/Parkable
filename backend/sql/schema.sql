-- Parkable Phase 2 storage preparation. PostGIS geography keeps proximity
-- searches in metres without application-side latitude/longitude math.
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS rules (
    id UUID PRIMARY KEY,
    location GEOGRAPHY(Point, 4326) NOT NULL,
    rule JSONB NOT NULL,
    source TEXT NOT NULL,
    parser_version TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS rules_location_gist_idx
    ON rules USING GIST (location);

-- Find rules within a 25 metre radius. Supply longitude before latitude:
-- SELECT id, rule, source, parser_version, created_at
-- FROM rules
-- WHERE ST_DWithin(
--     location,
--     ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
--     25
-- )
-- ORDER BY ST_Distance(
--     location,
--     ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
-- );
