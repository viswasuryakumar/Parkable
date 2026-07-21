# Parkable — Build Guide

**"Can I park here right now?"** — A trustworthy AI parking verification system.

## Multi-Agent Development (since 2026-07-16)

Three agents build this project in parallel: **Claude Code (manager)**, **Codex**, and **GitHub Copilot** (both VS Code-side). Coordination lives in two files:

- `AGENTS.md` — team rules, file ownership map, toolchain, definition of done (all agents must follow)
- `PROGRESS.md` — the task board; each agent claims/updates only its own task rows and appends to the log

Claude Code responsibilities beyond its own tasks: keep the build green, triage `PROGRESS.md` Requests & Blockers, review Codex/Copilot deliverables after they mark DONE, and report project status when the user asks. Task areas are partitioned by directory so no agent ever waits on another.

**Toolchain note**: project targets **Java 25** (`C:\Users\018316532\tools\jdk-25.0.2`) since the 2026-07-16 Copilot modernize upgrade; user-level `JAVA_HOME` may still point to JDK 21 — set it per-session.

## Philosophy

- **Perception ≠ Decision**: LLM extracts rules from photos; Java rules engine decides (100% testable)
- **Dual-source, single schema**: Gov data + camera scans, both normalized, both traceable
- **Architecture-first**: The parking app is the demo; the pluggable architecture is the product
- **Honest uncertainty**: Low confidence → "retake photo," never confidently wrong YES

## Tech Stack (Phase 1–4)

| Layer | Tech |
|-------|------|
| Mobile | React Native (Expo) |
| API | AWS Lambda (Java 21) |
| Rules Engine | Plain Java (fully unit-tested) |
| Database | Supabase Postgres + PostGIS |
| Vision | OpenRouter (cheapest available vision model) or Claude Vision, via `VisionExtractor` interface |
| Evals | LangSmith |

## 5-Phase Build Plan

Work proceeds phase-by-phase, in order, at whatever pace is feasible. A phase is "done" when its deliverables pass tests, not when a calendar date arrives.

### Phase 1: Core – Rules Engine & Extraction
**Goal**: Deterministic verdict + traced reason, local CLI working

**Tasks**:
- [ ] JSON rule schema (parking rule structure)
- [ ] Rules engine in Java (midnight crossing, "ANY TIME", nth-weekday cleaning, holidays, directional arrows)
- [ ] Unit tests (edge cases: DST, holidays, rule conflicts)
- [ ] VisionExtractor interface + Claude Vision provider
- [ ] JSON schema validation + 1-retry flow
- [ ] Local CLI: `image-in → verdict + trace-out`

**Learning**: Java time handling, regex for rule parsing, test-first design

### Phase 2: AWS Backend
**Goal**: Scalable API answering /scan, /check, /nearby from cloud

**Tasks**:
- [ ] Supabase setup + Postgres schema (rules table with GEOGRAPHY location, JSONB rules, source tag)
- [ ] PostGIS spatial index (GiST, 25m geospatial cache)
- [ ] AWS S3 bucket for raw photos
- [ ] AWS Lambda function (Java 21) wrapping rules engine
- [ ] API Gateway routes: POST /scan, GET /check, GET /nearby
- [ ] AWS SSM Parameter Store (API keys, DB creds)

**Learning**: Lambda cold start, PostGIS queries, spatial indexing

### Phase 2.5: Gov Data ETL (multi-city)
**Goal**: Auto-answer from government open-parking-data when available — **not SF-only**;
NYC, Chicago, LA, SF, and Seattle all have verified real endpoints (see
`docs/plans/phase2.5-gov-data-etl.md`), and the two-adapter design (Socrata + ArcGIS) covers
most future US cities too.

**Tasks**:
- [ ] New `GovDataFeed` interface (bulk enumeration — distinct from Phase 1's point-query `SignSource`, which stays camera-only) + `SocrataGovDataFeed`, `ArcGisGovDataFeed` implementations (generic transport, reused across all cities)
- [ ] `GovRuleMapper` interface + one mapper per city (NYC, Chicago, LA, SF, Seattle) — each verifies real field names live before coding, not from guesses
- [ ] Batch ETL job (`GovDataImportCli`): normalize + load into PostGIS with `source=gov_data` tag, idempotent re-run
- [ ] /check, /nearby endpoints already return gov data when within 25m once it's loaded — **zero handler changes needed**, Phase 2's cache/parser_version logic is generic

**Learning**: ETL patterns, normalizing heterogeneous parking rule formats

### Phase 3: Mobile App
**Goal**: End-to-end: open app → auto-check location → see verdict + retake flow

**Tasks**:
- [ ] Expo project setup (camera, GPS, native modules)
- [ ] Auto-check on app open (call /check with current GPS)
- [ ] Verdict screen (verdict + reason + countdown + "move car" button)
- [ ] Camera flow (capture → upload to /scan → poll for result)
- [ ] Retake UX (low confidence → prompt for clearer/wider photo)

**Learning**: Expo lifecycle, camera/GPS permissions, async state management

### Phase 4: Evals & Metrics
**Goal**: Published accuracy + regression gate

**Tasks**:
- [ ] Collect 100–150 labeled sign photos (manual street walks; small eval-only Street View set)
- [ ] Tag failures (OCR / arrow / schema / reasoning)
- [ ] LangSmith eval runs on every prompt/model change
- [ ] Publish accuracy % in README
- [ ] Log counters: cache hit rate, avg latency, LLM calls saved, cost/scan

**Learning**: Eval design, LangSmith workflows, failure analysis

## Architecture Rules (Enforce in Code Review)

1. **Lambda handlers: zero business logic** — core runs outside Lambda unchanged
2. **SignSource + VisionExtractor: interfaces from day one** — adding source/provider must not touch engine
3. **Rules engine: evaluation instant as parameter** — never hard-coded "now"
4. **Every stored rule: source + version tag** — cache hits require current parser_version

## Success Criteria

- ✓ Verdict correct on ≥90% of eval benchmark
- ✓ Gov-data/cache answer <1s; fresh scan <10s
- ✓ **Zero LLM-decided verdicts** — every answer traceable to rule + engine + source
- ✓ Prompt/model changes gated by regression evals

## Dev Setup

```bash
# Phase 1: Java rules engine + CLI
cd backend
mvn clean install
java -jar target/parkable-cli.jar path/to/photo.jpg

# Phase 2: AWS backend (requires AWS account + Supabase)
# See backend/README.md

# Phase 3: Mobile app
cd mobile
npm install
npx expo start

# Phase 4: Evals
# See evals/README.md
```

## Key Files to Know

- `backend/src/main/java/com/parkable/engine/RulesEngine.java` — core verdict logic
- `backend/src/main/java/com/parkable/extraction/VisionExtractor.java` — interface for LLM extraction
- `backend/src/main/java/com/parkable/datasource/SignSource.java` — interface for data sources
- `docs/schema.md` — JSON rule schema (canonical source of truth)
- `backend/src/test/java/com/parkable/engine/RulesEngineTest.java` — edge case tests

## Running Locally

**Phase 1 only** (rules + extraction):
```bash
mvn clean install -DskipTests
java -cp target/classes com.parkable.cli.ScanCLI src/test/resources/sign.jpg
```

**With AWS** (Phase 2+):
```bash
export AWS_PROFILE=parkable
mvn clean package
sam local start-api  # runs Lambda locally
```

## Deferred — Design Room (v2+, NOT Phase 1)

- More cities for gov ETL (Mapillary as third SignSource — not Google Street View)
- Raspberry Pi self-host (thin adapters → swappable, not rewrite)
- Future-time planning ("staying 3 hours — when must I move?")
- Crowdsourced sign sharing (schema already supports it)
- Failure-analysis dashboard

## Notes

- **Google Street View**: bulk harvesting prohibited by ToS. Manual eval-only sets OK; automated coverage must use Mapillary (open-licensed).
- **Dual-source contract**: SignSource + VisionExtractor interfaces mean adding new city data or new LLM provider = config/new class, zero engine changes.
- **Reproducibility**: Every scan stores photo + parsed JSON + parser_version + GPS + timestamp → old scans reprocessable when extraction improves.
