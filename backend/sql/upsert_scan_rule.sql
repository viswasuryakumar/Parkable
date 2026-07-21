-- Persist one extracted rule at a scan's GPS point. Bind every value through a
-- JDBC PreparedStatement: id, longitude, latitude, rule JSON, source,
-- parser_version, extracted_at. Longitude is the x coordinate and comes first.
-- The rule JSON carries a namespaced _parkable object with extraction-level
-- provenance (photo reference, extraction ID, timestamp, and envelope header)
-- so RuleRepository's extraction reads remain reconstructible.
INSERT INTO rules (id, location, rule, source, parser_version, created_at)
VALUES (
    :id,
    ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
    :rule_json::jsonb,
    :source,
    :parser_version,
    :extracted_at
)
ON CONFLICT (id) DO UPDATE SET
    location = EXCLUDED.location,
    rule = EXCLUDED.rule,
    source = EXCLUDED.source,
    parser_version = EXCLUDED.parser_version,
    created_at = EXCLUDED.created_at;
