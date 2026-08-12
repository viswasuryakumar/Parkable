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

-- User-flagged "this rule looks wrong/outdated" reports. Purely a log for
-- later human review - a report never automatically changes or removes a
-- rule (a bad-faith report must not be able to take down a real one).
CREATE TABLE IF NOT EXISTS rule_reports (
    id UUID PRIMARY KEY,
    rule_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    device_id TEXT NOT NULL,
    reported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS rule_reports_rule_id_idx
    ON rule_reports (rule_id);

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

-- Web Push subscriptions for the browser build (mobile web can't schedule a
-- local notification the way the native app can, so the reminder has to be
-- pushed from the server). Keyed by endpoint because that is what the browser
-- re-issues verbatim on every resubscribe - so an upsert on it keeps one row
-- per browser instead of accumulating a new row each visit.
CREATE TABLE IF NOT EXISTS push_subscriptions (
    id UUID PRIMARY KEY,
    endpoint TEXT NOT NULL UNIQUE,
    p256dh TEXT NOT NULL,
    auth TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
