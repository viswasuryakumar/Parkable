# Parkable — Phase 2: AWS Backend

**Status**: Active. This document is the authoritative spec for Phase 2 — agents code against
this, not against each other's work-in-progress. Companion study material: `PHASE-2-STUDY.md`.

**Goal**: the Phase 1 engine, unchanged, answering `POST /scan`, `GET /check`, `GET /nearby`
from the cloud. Gov-data/cache answers <1s; fresh scans <10s.

---

## 1. Architecture

```
Mobile app
   │ HTTPS
API Gateway ──► Lambda handlers (com.parkable.lambda — ZERO business logic)
                    │
        ┌───────────┼───────────────────────┐
        ▼           ▼                       ▼
  RuleLookup    VisionExtractor        RuleRepository
  (port)        (Phase 1, unchanged)   (Phase 1 seam)
        │           │                       │
        ▼           ▼                       ▼
  Supabase      Anthropic API          Supabase
  PostGIS       (key from SSM)         (same DB)
                    photos → S3
```

Phase 1 rules that carry over verbatim: handlers stay logic-free; evaluation instant is a
parameter (the handler edge is a legitimate `Clock` read, like `ScanCLI.main`); every stored
rule carries `source` + `parser_version`.

## 2. Decisions (made — do not relitigate in code)

- **D1 — Compile target**: `maven.compiler.release=21`. AWS's managed Java runtime tops out
  at 21; we use no post-21 language features. The JDK 25 toolchain stays for building.
- **D2 — Lambda events**: REST-style `APIGatewayProxyRequestEvent/ResponseEvent` from
  `aws-lambda-java-events` (matches SAM's default and `sam local start-api`).
- **D3 — /scan v1 carries the photo as base64 JSON field.** The presigned-URL upload flow is
  a Phase 2.1 refinement once the basic path works end-to-end.
- **D4 — Wiring**: handlers construct an in-memory stack by default; the Postgres/S3/SSM
  stack is selected by environment variables (`PARKABLE_DB_URL` present → Postgres). Local
  tests never need cloud credentials.
- **D5 — Cache validity**: a stored rule answers a /check only if its `parser_version`
  matches the current parser (Phase 1 reproducibility contract).
- **D7 — Default extraction provider is OpenRouter, not Claude directly (2026-07-20 user decision)**:
  `ExtractorFactory` prefers `OPENROUTER_API_KEY` over `ANTHROPIC_API_KEY` (Claude still works if
  only that key is set — the Strategy interface made this a config change, zero engine edits).
  OpenRouter proxies many vendors' vision-capable models through one OpenAI-compatible API, so a
  cost-sensitive deployment can point at whichever model is currently cheapest via one env var,
  `OPENROUTER_MODEL` (default `openai/gpt-4o-mini` — check https://openrouter.ai/models,
  filter "supports image input", before trusting the default is still the cheapest). No official
  OpenRouter Java SDK exists; `OpenRouterVisionExtractor` talks its REST API directly via
  `java.net.http.HttpClient`. SAM template's SSM parameter is `/parkable/openrouter-api-key`
  (`OpenRouterApiKeyParameter`), replacing the earlier Anthropic-only parameter.
- **D6 — PostgresRuleRepository constructor contract**: a single public constructor
  taking the JDBC URL as one `String` (e.g. `public PostgresRuleRepository(String jdbcUrl)`),
  implementing both `RuleRepository` and `RuleLookup` on the same class. C9's
  `StorageStack` loads it reflectively by this exact signature (`Class.forName` +
  `getConstructor(String.class)`) so wiring works whether or not X4 has landed yet —
  match it exactly or the reflective wiring throws a clear "wrong constructor" error.

## 3. API Contract (Copilot's mobile client + SAM template code against THIS)

### GET /check?lat=<double>&lng=<double>[&at=<ISO instant>][&zone=<ZoneId>][&side=LEFT|RIGHT]
- 200: `{"verdict":"PARKABLE|NOT_PARKABLE|DEPENDS","reason":string|null,"rule_id":string|null,
  "valid_until":ISO-instant|null,"source":"gov_data|camera_scan","trace":[string]}`
- 404: `{"status":"NO_DATA","message":"No rule data within 25m. Scan the sign."}`
- 400: `{"status":"BAD_REQUEST","message":...}` (missing/invalid lat/lng)

### GET /nearby?lat=<double>&lng=<double>[&radius=<metres, default 1000, max 2000>]
- 200: `{"rules":[{"rule_id":string,"description":string,"source":string,
  "parser_version":string,"days":string,"hours":string,"lat":double,"lng":double,
  "distance_m":number}]}` (empty list is a valid 200; ordered nearest-first per D-era
  `ST_Distance` ordering, unchanged since Phase 2)
  - `days`/`hours` are human-readable, never raw enum values — "Every day"/"Any time" when
    the sign has no restriction on that axis, never a guess (2026-07-22, user-testing finding:
    the app must show what schedule the sign actually said, or the honest absence of one).
  - `lat`/`lng` are the STORED RULE's own point, not the query point — every writer already
    has this (scan GPS, gov record geometry). `distance_m` is server-computed (PostGIS
    `ST_Distance`/haversine, same source as the query's own sort order); the client derives
    compass bearing and reverse-geocodes a street name from `lat`/`lng` itself.

### POST /scan  body: `{"photo_base64":string,"media_type":"image/jpeg|png|webp|gif",
  "lat":double,"lng":double[,"at":ISO][,"zone":ZoneId][,"side":"LEFT|RIGHT"]}`
- 200: same shape as /check 200 (verdict of the freshly scanned sign)
- 422: `{"status":"NEEDS_REVIEW","message":<honest retake message>}`
- 400: bad request shape

## 4. Ports (interfaces owned by Claude Code in `com.parkable.lambda.port`)

```java
public interface RuleLookup {                       // geo query seam
    List<StoredRule> findWithin(double latitude, double longitude, double radiusMeters);
}
public record StoredRule(Rule rule, String source, String parserVersion) {}
```

Scan persistence reuses Phase 1's `RuleRepository.save(ExtractionRecord)` (Codex X1) —
`ExtractionRecord` already carries GPS + parser_version.

## 5. Task Partition (non-blocking; IDs on PROGRESS.md)

| ID | Owner | Deliverable |
|----|-------|-------------|
| C8 | Claude Code | `com.parkable.lambda`: CheckHandler, NearbyHandler, ScanHandler + port + response DTOs + tests over in-memory fakes |
| C9 | Claude Code | SSM-backed config + env-var wiring (D4); pom/packaging for the Lambda artifact |
| C10 | Claude Code | Review X4/X5/P3/P4; `sam local` end-to-end smoke test (needs U2) |
| X4 | Codex | `com.parkable.repository.postgres.PostgresRuleRepository`: implements `RuleRepository` + `RuleLookup`; JDBC `PreparedStatement`s only; `ST_DWithin` per `backend/sql/schema.sql` (lng-first!); parser_version filter (D5); config from env vars; unit tests for SQL/mapping + live integration test guarded by `PARKABLE_DB_URL` |
| X5 | Codex | `backend/sql/`: upsert statement for scan writes + cache-lookup query incl. parser_version filter, as documented .sql files |
| P3 | Copilot | `infra/`: SAM `template.yaml` (3 routes → 1 Lambda, S3 bucket, SSM parameter refs, least-privilege role), `samconfig.toml.example`, `infra/README.md` with exact deploy + `sam local` steps |
| P4 | Copilot | `mobile/`: base URL from app config; wire CameraScreen upload to the /scan contract (base64 v1); handle 422 retake flow |
| U1 | **User** | Supabase: create project → SQL editor → run `backend/sql/schema.sql` → copy the *pooled* connection string |
| U2 | **User** | AWS account + AWS CLI v2 + SAM CLI + Docker Desktop installed |
| U3 | **User** | Anthropic API key (console.anthropic.com) — only when live extraction is wanted |

U1–U3 block **deployment**, not coding: X4's live test and C10's smoke test wait on them;
everything else proceeds now.

## 6. New Ownership (adds to AGENTS.md map)

- `backend/src/main/java/com/parkable/lambda` (+tests) — Claude Code
- `backend/src/main/java/com/parkable/repository/postgres` (+tests) — Codex
- `infra/` — Copilot

## 6.1 Packaging note (for P3's SAM template)

No new build step needed: the existing Shade-built `backend/target/parkable-cli.jar`
(Maven `package`) already contains `com.parkable.lambda.*` plus every dependency. The SAM
template just needs `CodeUri: ../backend/target/parkable-cli.jar` and, per route,
`Handler: com.parkable.lambda.CheckHandler::handleRequest` (swap the class per route). The
jar's inert CLI `Main-Class` manifest entry is harmless — Lambda invokes the handler class
directly by name, ignoring it.

## 7. Definition of Done additions for Phase 2

- No AWS call, DB connection, or API key required by `mvn test` — cloud-touching tests are
  env-var-guarded and skipped by default.
- Handlers contain orchestration only; a reviewer must be able to read a handler top to
  bottom and find no `if` about parking rules.
