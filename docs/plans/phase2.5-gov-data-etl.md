# Parkable — Phase 2.5: Gov Data ETL (Multi-City)

**Status**: Active. This document is the authoritative spec for Phase 2.5 — agents code against
this, not against each other's work-in-progress. Supersedes the SF-only framing of Phase 2.5 in
`CLAUDE.md`'s original task list (user request 2026-07-20: "keep all the other cities available
data too").

**Goal**: batch-load government parking-rule data for multiple US cities into the same
`rules` table Phase 2 already built, tagged `source='gov_data'`, so `/check` and `/nearby`
answer instantly from cache without waiting on a camera scan — **zero changes to the
Lambda handlers, `RuleRepository`, or PostGIS schema**, because Phase 2 already built the
`source` + `parser_version` columns generically. This is purely an ETL ingestion pipeline
that feeds the existing pipe.

---

## 1. Why this is (mostly) cheap: reusing Phase 1's seams

Phase 1's `SignSource` (`fetch(lat, lng, zone) → List<Rule>`) is a **point-query** interface —
right for "what's near this GPS pin," which is what `CameraScanSignSource` does. A batch ETL
job needs the opposite operation: "give me every row in this city's dataset," paginated,
independent of any single point. Forcing bulk enumeration through a point-query signature
(e.g. a giant-radius `fetch()` call) would be a real interface misuse — no clean lat/lng/radius
describes "all of Chicago" — so this introduces one small new interface for the bulk side,
alongside the point-query one:

```java
public interface GovDataFeed {
    Iterator<JsonNode> fetchAll();   // adapter pages internally; caller just iterates
}
```

Everything downstream of that is reuse: `RuleRepository.save(ExtractionRecord)` (Phase 1/2,
unchanged) is the sink regardless of whether the `ExtractionRecord` came from a camera scan or
a gov-data row — its "photo reference" field is just a provenance string, repurposed here as
the source dataset's natural row id (e.g. `"nyc:afgb-4qw7:<SIGNID>"`) so `source="gov_data"`
records are traceable exactly like camera scans are. No `RuleRepository`/schema change needed.

## 2. Verified real-world data sources (curl-verified 2026-07-20, not guessed)

Two transport platforms cover every city checked so far — most large-city open-data portals
run one of these two, so the adapter count stays at 2 regardless of how many cities are added
later.

| City | Platform | Dataset / Endpoint | Notes |
|------|----------|---------------------|-------|
| NYC | Socrata (SODA) | `data.cityofnewyork.us` dataset `afgb-4qw7` ("NY Parking Signs") | Sign-level: codes, descriptions, street + side-of-street, State Plane x/y. (First-guessed dataset `xswq-wnv9` requires login — do not use.) |
| Chicago | Socrata (SODA) | `data.cityofchicago.org` dataset `u9xt-hiju` ("Parking Permit Zones") | Zone id/name, address range, street, ward, buffer flag (buffer = ordinance-only, no posted sign — lower confidence). |
| LA | Socrata (SODA) | `data.lacity.org` datasets `s49e-q6j2` (metered parking, direct WGS84 lat/lng) + `jp2s-nfz4` (calendar/seasonal sign locations, GeoJSON polygons) | Two datasets, load both. |
| SF | Esri ArcGIS FeatureServer | `services.sfmta.com/arcgis/rest/services/DataSF/master/FeatureServer/24` ("MTA.parkingregulations", polyline layer) | The Socrata-looking dataset (`qbyz-te2i`) is a map visualization only (`assetType: "map"`), not real data — this FeatureServer is the actual source, found via that dataset's own metadata. |
| Seattle | Esri ArcGIS FeatureServer | Org `services.arcgis.com/ZOyb2t4B0UYuYNYH/arcgis/rest/services/...` — `Peak_Hour_Parking_Restrictions/FeatureServer/3` and `SDOT_Street_Signs/FeatureServer/1` | Discovered via Seattle's ArcGIS Open Data DCAT feed (`data-seattlecitygis.opendata.arcgis.com/api/feed/dcat-us/1.1.json`) rather than a guessed org-hash URL (first guess 400'd) — every ArcGIS Open Data portal exposes this same DCAT discovery feed, so future cities can be found the same way without guessing. |

**Adding a 6th+ city later**: check `{portal}/api/feed/dcat-us/1.1.json` first (ArcGIS Open Data
portals all expose it) or search `{city}.data.socrata.com`/known Socrata domains. Most US
municipal open-data portals are one of these two platforms.

**Exact field names are intentionally NOT catalogued here.** The table above records verified
*endpoints*, not verified *schemas* — field-level shape must be confirmed with a live
`$limit=3` (Socrata) / `resultRecordCount=3` (ArcGIS) query at the start of each mapper task
(X8/X9 below), not assumed from this doc or from the speculative Phase-1-era tables in
`docs/schema.md` §"Normalization Rules: Multi-City Mapping". Writing a mapper against a
guessed field name is how the SF Socrata mistake happened upstream of this doc.

## 3. Architecture

```
GovDataImportCli (com.parkable.cli — Claude Code)
   │  per city: GovCityConfig { name, GovDataFeed instance, mapper }
   ▼
GovDataFeed impls (com.parkable.datasource — Codex)
   ├── SocrataGovDataFeed   (generic SODA transport: domain + dataset id + $limit/$offset paging)
   └── ArcGisGovDataFeed    (generic FeatureServer transport: query URL + resultOffset paging)
        │
        ▼  raw JSON records (Iterator<JsonNode>, fully paginated)
   GovRuleMapper impls (com.parkable.datasource — Codex, one per city)
        │  NycSignMapper, ChicagoSignMapper, LaSignMapper, SfSignMapper, SeattleSignMapper
        ▼  List<Rule> (source="gov_data", parser_version=mapper's own version tag)
   RuleRepository.save(ExtractionRecord)  (Phase 1/2 seam, unchanged)
        ▼
   Supabase Postgres/PostGIS `rules` table
        ▲
   /check, /nearby  (Phase 2 handlers, UNCHANGED — already query by radius + parser_version)
```

`SignSource` (Phase 1, point-query) is unrelated to this pipeline — it stays exactly as-is for
`CameraScanSignSource`, and is not implemented by anything here. `GovDataFeed` (bulk enumeration)
is the new, separate seam this phase adds. The two `GovDataFeed` transport adapters are dumb and
reusable; all city-specific knowledge lives in the mapper. This mirrors `docs/schema.md`'s
existing per-city mapping tables — those tables become each mapper's javadoc/spec, now backed by
a real verified endpoint instead of a guess.

## 4. Decisions (made — do not relitigate in code)

- **G1 — Batch ETL, not live per-request calls.** Gov data changes on the order of
  weeks/months, not seconds. Loading it into the same PostGIS cache Phase 2 already built
  keeps `/check` fast (<1s, per `CLAUDE.md` success criteria) and requires zero handler
  changes. Nightly/on-demand re-run, not a request-path dependency.
- **G2 — `GovRuleMapper` interface** (new, small, lives beside `GovDataFeed` and `SignSource`
  in `com.parkable.datasource`):
  ```java
  public interface GovRuleMapper {
      List<Rule> map(JsonNode rawRecord);
      String parserVersion();   // e.g. "gov-nyc-mapper-v1" — same reproducibility contract as VisionExtractor (Phase 1 rule: every stored rule carries parser_version)
  }
  ```
  One implementation per city. A record that can't be confidently mapped (missing
  hours/days, ambiguous free-text) is dropped, not guessed — mirrors the "honest
  uncertainty" philosophy from camera extraction; gov data has the same bar.
- **G3 — Buffer/ordinance-only zones (Chicago) get tagged, not dropped.** Per
  `docs/schema.md`'s existing Chicago mapping: `source='gov_data'` still applies, but a
  `confidence_score < 1.0` (schema already supports this field) marks "law exists, no
  posted sign to photograph" so the rules engine / UI can caveat it appropriately.
- **G4 — Idempotent re-import.** Re-running the ETL for a city must not duplicate rows —
  reuse Phase 2's `stableRuleId`-style deterministic UUID derivation (from
  `PostgresRuleRepository`), keyed off each source's natural id (NYC `SIGNID`, Chicago
  `ZONE_ID`, etc.) so a nightly re-run naturally upserts.
- **G5 — App tokens optional, not required.** Socrata endpoints work unauthenticated;
  `SOCRATA_APP_TOKEN` env var is read if present (avoids throttling on large pages) but its
  absence must not break anything, matching Phase 2's "no cloud credential required for
  default local dev" bar.
- **G6 — Pagination is the adapter's problem, not the mapper's.** `SocrataGovDataFeed`/
  `ArcGisGovDataFeed` fully page through a dataset internally (SODA `$limit`/`$offset`;
  ArcGIS `resultOffset`/`resultRecordCount`) and hand the mapper one record at a time —
  keeps mappers trivially unit-testable against small canned fixture JSON.
- **G7 — `GovDataFeed` is a new interface, distinct from `SignSource`.** Point-query
  (`SignSource`, Phase 1) and bulk-enumeration (`GovDataFeed`, this phase) are different
  contracts with different callers — collapsing them would force one side to fake the other
  (e.g. a `SignSource.fetch` call that secretly ignores lat/lng and returns everything). Both
  live in `com.parkable.datasource`; neither implements the other.

## 5. Task Partition (non-blocking; IDs continue Phase 2's C/X/P numbering on PROGRESS.md)

| ID | Owner | Deliverable |
|----|-------|-------------|
| C13 | Claude Code | This plan doc + `CLAUDE.md`/`PROGRESS.md`/`AGENTS.md` updates (done alongside this doc) |
| C14 | Claude Code | `com.parkable.cli.GovDataImportCli` — composition root wiring `GovCityConfig` (city name → `GovDataFeed` + `GovRuleMapper` pair) to `RuleRepository.save`; per-city or `--all` CLI invocation; reflective/deferred loading of X6-X9 classes if not yet landed (same pattern as Phase 2's `StorageStack`, so this can be written before X6-X9 finish) |
| C15 | Claude Code | Review X6-X9 deliverables; run the importer against at least one real live city dataset as a smoke test; confirm `/nearby` returns gov-tagged rules end-to-end |
| X6 | Codex | `com.parkable.datasource.SocrataGovDataFeed` implementing new `GovDataFeed` — generic SODA transport (domain, dataset 4x4 id, optional app token), full pagination, returns raw `JsonNode` records; unit tests against canned fixture JSON (no live network in default test run) |
| X7 | Codex | `com.parkable.datasource.ArcGisGovDataFeed` implementing `GovDataFeed` — generic FeatureServer transport (query URL), full pagination via `resultOffset`, same fixture-based testing approach |
| X8 | Codex | `GovRuleMapper` interface + `NycSignMapper`, `ChicagoSignMapper`, `LaSignMapper` (2 LA datasets → one mapper or two, Codex's call) — **first step of each: live `$limit=3` query against the real dataset to confirm actual field names before writing the mapper**, then unit tests with a small fixture captured from that same query |
| X9 | Codex | `SfSignMapper`, `SeattleSignMapper` (ArcGIS-backed; Seattle needs both `Peak_Hour_Parking_Restrictions` and `SDOT_Street_Signs` layers — Codex's call whether one mapper or two) — same live-verify-first approach |
| P5 | Copilot | (optional/nice-to-have, not blocking) `mobile/`: surface `source` (`gov_data` vs `camera_scan`) on the verdict/nearby UI so users can see when an answer came from official city data vs a community photo scan |

## 6. Ownership (no AGENTS.md change needed)

- `backend/src/main/java/com/parkable/datasource` (+tests) — already Codex (Task X2); X6-X9
  extend the same package.
- `backend/src/main/java/com/parkable/cli` (+tests) — already Claude Code; C14 extends it.

## 7. Definition of Done additions for Phase 2.5

- No live network call in the default `mvn test` run — Socrata/ArcGIS adapter tests run
  against canned fixture JSON; a real-endpoint smoke test is env-var-guarded
  (`EnabledIfEnvironmentVariable`), same convention as Phase 2's Postgres live test.
- Every gov-sourced `Rule` carries `source="gov_data"` and a mapper-specific
  `parser_version` (G2) — the Phase 1 reproducibility rule applies to gov data exactly as
  it does to camera scans.
- A mapper that cannot confidently normalize a record drops it (logs/skips), never guesses
  — same honest-uncertainty bar as `VisionExtractor`.
- Re-running the importer for an already-loaded city does not create duplicate rows (G4).
