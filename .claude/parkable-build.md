---
name: parkable-build
description: Building Parkable step-by-step with learning checkpoints
---

# Building Parkable: Phase-by-Phase Skill

This skill guides you through building Parkable with regular learning updates and checkpoints.

## Pre-requisites (Learning Path)

### Required Knowledge (Learn in Parallel)
- **Java 21** fundamentals (streams, records, sealed classes)
- **Temporal API** (java.time, LocalDateTime, ZoneId) — critical for rule evaluation
- **JUnit 5** for unit testing
- **Maven** basics (dependencies, profiles, plugins)
- **JSON schema** design (required fields, enums, time ranges)
- **Regex** for parsing sign text
- **React Native / Expo** basics (lifecycle, hooks, native modules)
- **PostGIS** fundamentals (ST_DWithin, GEOGRAPHY type, GiST index)
- **AWS Lambda** (Java runtime, cold start, environment variables)
- **LangSmith** eval workflows

### Required Tools
- **Java 21** JDK (https://jdk.java.net/21/)
- **Maven 3.9+** (https://maven.apache.org/)
- **Node.js 18+** (for Expo)
- **AWS CLI** v2 (for Phase 2+)
- **PostgreSQL 15+** local dev instance (or Supabase cloud)
- **Git** (version control, commit messages trace decisions)

---

## Phase 1: Core Rules Engine

### What You're Building
A deterministic Java rules engine that:
- Reads JSON parking rules
- Evaluates them against a given instant in time
- Returns: verdict (PARKABLE/NOT_PARKABLE/DEPENDS) + triggering rule + next boundary
- Never uses hard-coded "now" (always passed as parameter)

### Learning Checkpoints
1. **Java Temporal API Deep Dive**
   - LocalDateTime, ZoneId, ZonedDateTime
   - Handling DST transitions
   - Temporal ranges (e.g., "Mon–Fri 4–6 PM")
   - Holiday calendars
   - **Exercise**: Write a function to detect if a given time falls in "Mon–Fri 4–6 PM" (anywhere in the world, honoring DST)

2. **Rule Schema Design**
   - Sign types: time-based, permit-required, no-parking, street-cleaning
   - Directional modifiers (left side, right side, arrow pointing)
   - Effective dates, exceptions
   - **Exercise**: Sketch JSON for "No Parking Tue 8–10 AM, except holidays"

3. **Test-First Java Development**
   - Edge cases: midnight crossing (11 PM – 2 AM), DST boundary, leap seconds
   - Holiday calendars (Thanksgiving, moving holidays)
   - Conflicting rules ("No Parking Any Time" vs. "Parking OK Weekends")
   - **Exercise**: Write 10 edge-case tests before writing engine code

### Deliverables
- [ ] `docs/schema.md` — canonical rule JSON schema
- [ ] `backend/src/main/java/com/parkable/model/Rule.java` — Java record matching schema
- [ ] `backend/src/main/java/com/parkable/engine/RulesEngine.java` — verdict logic
- [ ] `backend/src/test/java/.../RulesEngineTest.java` — 50+ unit tests
- [ ] `backend/src/main/java/com/parkable/cli/ScanCLI.java` — local CLI: image → verdict

### Build Command
```bash
cd backend
mvn clean install
mvn test  # all tests must pass
java -jar target/parkable-cli.jar path/to/parking-sign.jpg
```

### What to Update Me On
- ✓ Any new Java concept you learned (e.g., sealed classes, records)
- ✓ Tricky edge case you discovered (e.g., DST crossing)
- ✓ Test coverage: total test count, % of edge cases passing
- ✓ Schema design: how many rule types, any conflicts found

---

## Phase 2: AWS Backend

### What You're Building
A scalable cloud API wrapping the Phase 1 engine:
- POST /scan: upload photo → extract → store → return verdict
- GET /check: GPS coordinates → PostGIS lookup → cached answer (if 25m nearby)
- GET /nearby: GPS → all rules within 1 km

### Learning Checkpoints
1. **PostGIS Fundamentals**
   - GEOGRAPHY vs. GEOMETRY (use GEOGRAPHY for lat/lng)
   - ST_DWithin for 25m radius queries
   - GiST spatial indexes
   - **Exercise**: Query 50 parking rules, find all within 25m of a point

2. **AWS Lambda in Java**
   - Cold start minimization (context reuse, no reflection)
   - Environment variable injection (API keys from SSM)
   - Structured logging (CloudWatch)
   - **Exercise**: Deploy a "hello world" Lambda in Java 21

3. **S3 Photo Storage**
   - Presigned URLs (signed download links for photos)
   - Object metadata (GPS coordinates, timestamp)
   - Cost estimation (free tier: 5 GB)
   - **Exercise**: Upload a photo, retrieve via presigned URL

4. **Supabase / PostgreSQL Setup**
   - Row-level security (if needed)
   - JSONB columns for rule storage
   - Connection pooling
   - **Exercise**: Insert 10 rules, query by location, measure latency

### Deliverables
- [ ] Supabase project created (PostgreSQL 15+, PostGIS extension)
- [ ] `backend/schema.sql` — rules table (GEOGRAPHY + JSONB + source)
- [ ] `backend/src/main/java/.../LambdaHandler.java` — API Gateway handler
- [ ] API endpoints working: /scan, /check, /nearby
- [ ] AWS SSM parameters configured (VISION_API_KEY, DB_URL)
- [ ] S3 bucket created with proper CORS + lifecycle rules

### Build Command
```bash
cd backend
mvn clean package
sam local start-api  # runs on localhost:3000
curl http://localhost:3000/check?lat=37.77&lng=-122.41
```

### What to Update Me On
- ✓ Lambda cold start time (should be <500ms with Java 21)
- ✓ PostGIS query performance (check explain plans for slow queries)
- ✓ API response times: gov-data/cache (<1s), fresh scan (<10s)
- ✓ Any AWS account issues or cost surprises

---

## Phase 2.5: Gov Data ETL

### What You're Building
A batch job that:
- Downloads SF open parking data (SFMTA)
- Normalizes block-face rules into your JSON schema
- Loads into PostGIS with source=gov tag
- Enables instant /check answers without LLM

### Learning Checkpoints
1. **ETL Pipeline Design**
   - Extracting rules from heterogeneous formats (CSV, GeoJSON, PDF)
   - Handling missing fields ("No Parking" with no end time → 24h)
   - Conflict resolution (duplicate rules at same location)
   - **Exercise**: Write parser for SF SFMTA dataset format

2. **Data Normalization**
   - Mapping gov fields → your rule schema
   - Handling partial data (time only, no date range)
   - Version tracking (dataset date, parser version)
   - **Exercise**: Normalize 100 real SFMTA rules into your schema

3. **Batch Loading**
   - Bulk insert optimization (COPY vs. INSERT)
   - Transactional integrity (rollback on error)
   - Idempotence (safe to rerun)
   - **Exercise**: Load 5k rules in <5 minutes

### Deliverables
- [ ] `backend/src/main/java/.../GovDataSource.java` — interface impl
- [ ] `backend/src/main/java/.../SFParkingParser.java` — gov data parser
- [ ] `backend/batch/GovDataETL.java` — batch job
- [ ] `backend/sql/load_gov_data.sql` — idempotent load script
- [ ] Gov data tests: parse, normalize, load integrity checks

### Build Command
```bash
cd backend
mvn clean package -P batch
java -cp target/classes com.parkable.batch.GovDataETL \
  --input /path/to/sfdata.csv \
  --db-url jdbc:postgresql://...
```

### What to Update Me On
- ✓ Parser accuracy: % of rules successfully normalized
- ✓ Gov data coverage: how many blocks in SF, geographic distribution
- ✓ Load time: bulk insert optimization wins
- ✓ Cache hit rate on real test queries

---

## Phase 3: Mobile App

### What You're Building
React Native app (Expo) that:
- On open: auto-check current GPS location against /check
- Show instant verdict (gov data or cache hit)
- Camera flow: capture sign → POST /scan → poll → show result
- Retake UX: "photo too blurry/cropped, try again"

### Learning Checkpoints
1. **Expo & React Native Basics**
   - App lifecycle (focus, blur, permissions)
   - `expo-camera` module (preview, capture, permissions)
   - `expo-location` module (single-shot GPS read)
   - **Exercise**: Build a minimal app that opens camera and logs GPS

2. **State Management**
   - React hooks (useState, useEffect, useContext)
   - Polling for background uploads (POST /scan → poll /scan/status)
   - Retry logic (failed LLM extraction → manual retry)
   - **Exercise**: Build polling loop that retries 3x with backoff

3. **UX for Uncertainty**
   - Confidence scores from extraction (low conf → "retake photo")
   - Countdown UI (verdict valid until X time)
   - "Move car" notification flow
   - **Exercise**: Design and code countdown timer component

### Deliverables
- [ ] Expo project initialized
- [ ] `mobile/screens/VerdictScreen.tsx` — verdict + reason + countdown
- [ ] `mobile/screens/CameraScreen.tsx` — capture, upload, poll
- [ ] `mobile/services/api.ts` — /scan, /check, /nearby client
- [ ] Permission requests (camera, location)
- [ ] End-to-end test: open app → see gov-data verdict in <2s

### Build Command
```bash
cd mobile
npm install
npx expo start
# Scan QR code in Expo Go (iOS/Android)
```

### What to Update Me On
- ✓ Permissions working (iOS/Android differences if any)
- ✓ Auto-check latency (should be <1s for gov data)
- ✓ Camera flow UX feedback (usable? intuitive retake?)
- ✓ Any device-specific issues (older Android, iPad, etc.)

---

## Phase 4: Evals & Metrics

### What You're Building
Measurable accuracy + regression gate:
- Collect 100–150 labeled sign photos
- LangSmith eval runs on every prompt/model change
- Publish accuracy % in README (and per-failure category)
- Log operational metrics (cache hit rate, LLM calls saved, cost)

### Learning Checkpoints
1. **LangSmith Workflow**
   - Creating evaluation datasets
   - Defining evaluators (exact match, semantic similarity)
   - Regression runs (comparing model/prompt versions)
   - **Exercise**: Run a 5-photo eval, see which fail and why

2. **Failure Categorization**
   - OCR errors (text recognition failed)
   - Arrow errors (direction misidentified)
   - Schema errors (parsed into wrong field)
   - Reasoning errors (logic applied rule incorrectly)
   - **Exercise**: Tag 20 real failures by category, find patterns

3. **Metrics & Logging**
   - Cache hit rate (# cache hits / # checks)
   - Average latency (by source: gov vs. camera)
   - LLM calls saved (if cache hit: +1 saved)
   - Cost per scan (LLM tokens × rate)
   - **Exercise**: Log metrics after every scan, verify in CloudWatch

### Deliverables
- [ ] 100–150 labeled sign photos (structured benchmark set)
- [ ] `evals/eval_dataset.json` — photos + ground truth verdict + tags
- [ ] `evals/langsmith_config.yaml` — LangSmith eval setup
- [ ] `backend/src/main/java/.../Metrics.java` — logging + counters
- [ ] README updated with: accuracy %, failure breakdown, cost estimate

### Build Command
```bash
cd evals
python -m langsmith eval run --config langsmith_config.yaml
# View results in LangSmith dashboard
# See: accuracy %, per-failure-category breakdown
```

### What to Update Me On
- ✓ Total accuracy % (goal: ≥90%)
- ✓ Failure categories: which are most common? (e.g., "OCR 30%, Arrow 20%, Schema 15%")
- ✓ Operational metrics: cache hit rate, avg latency, cost/scan
- ✓ Regression test results (did model change improve or regress?)

---

## Code Quality & Design Principles

### Software Principles
- **SOLID Principles**: Single Responsibility, Open/Closed, Liskov Substitution, Interface Segregation, Dependency Inversion
- **DRY (Don't Repeat Yourself)**: Extract common logic into reusable utilities
- **Composition over inheritance**: Prefer interfaces + composition to deep hierarchies
- **Fail-fast**: Validate inputs early, throw meaningful exceptions
- **Immutability**: Use records, final fields, sealed classes (Java 21)

### Design Patterns in Parkable

1. **Strategy Pattern** (VisionExtractor, SignSource)
   - Different implementations (Claude Vision, Google Vision, etc.)
   - Same interface, swappable at runtime or config
   - Example: `VisionExtractor claude = new ClaudeVisionExtractor(...)`

2. **Factory Pattern** (RuleFactory, SourceFactory)
   - Centralize object creation
   - Makes it easy to add new rule types or data sources
   - Example: `Rule rule = RuleFactory.from(jsonNode)`

3. **Builder Pattern** (RuleBuilder, QueryBuilder)
   - Construct complex objects step-by-step
   - Readable, fluent API for testing
   - Example: `new RuleBuilder().withType(NO_PARKING).withDays(MONDAY_FRIDAY).build()`

4. **Repository Pattern** (RuleRepository)
   - Abstract storage layer
   - Switch implementations (Postgres → MongoDB) without changing business logic
   - Example: `RuleRepository repo = new PostgresRuleRepository(...)`

5. **Adapter Pattern** (VisionExtractor adapts LLM APIs to unified interface)
   - Normalize external APIs into your domain model
   - Makes swapping providers trivial

### Code Style & Commenting

**Comment philosophy**: Comment the *why*, not the *what*.
- ❌ Bad: `int day = 5; // day of week`
- ✓ Good: `// Monday = 1, Sunday = 7 (ISO-8601 for timezone consistency)`

**When to comment**:
- Non-obvious logic (why this rule is evaluated before that)
- Edge cases (DST boundary, midnight crossing)
- Performance decisions (why PostGIS GiST index needed)
- Constraints (this query must run in <100ms)

**Code structure**:
```java
// Good: clear class responsibilities
public interface VisionExtractor {
    /// Extracts parking rules from an image.
    /// Returns structured JSON; never decides verdict (engine's job).
    /// Throws VisionExtractionException on confidence < threshold.
    ExtractionResult extract(BufferedImage image) throws VisionExtractionException;
}

// Good: sealed class limits implementations
public sealed class Rule permits TimeBasedRule, PermitRule, NoParking {
    // Enforcement: only these types exist
}

// Good: record for immutability + equals/hashCode
public record RuleMatch(Rule rule, LocalDateTime validUntil, String reason) { }
```

**Testing culture**:
- Unit tests for pure logic (RulesEngine)
- Integration tests for storage (PostGIS queries)
- Property-based tests for edge cases (Quicktheories: "all times should match rule boundaries")
- Every new feature = tests first (TDD mindset)

---

## Incremental Workflow (Per Phase)

For each phase:

1. **Start**: Read this section + CLAUDE.md Phase details
2. **Learn**: Do learning checkpoint exercises (can run in parallel)
3. **Code**: Build deliverables incrementally (commit frequently)
4. **Test**: Unit tests → integration tests → end-to-end
5. **Update me**: Report metrics, learnings, blockers
6. **Review**: Ask for code review before moving to next phase

## When You're Stuck

- **Temporal edge case?** Ask: "What time does the rule apply?" (DST? Midnight crossing? Holiday?)
- **Schema question?** Check `docs/schema.md` (source of truth)
- **AWS mystery?** Run locally first (SAM, Supabase local, etc.)
- **Performance slow?** Use EXPLAIN ANALYZE (SQL), CloudWatch metrics (Lambda)
- **Test failing?** Add logging, check exact JSON structure, trace through engine step-by-step

## Phase Handoff Checklist

Before moving to next phase:
- [ ] All tests pass locally
- [ ] Code follows architecture rules (see CLAUDE.md)
- [ ] No LLM "guesses" (only extracted rules + engine)
- [ ] Metrics logged or manually verified
- [ ] Learnings documented (new thing = note in README)

---

**You're building a production-grade system. Slow down, test thoroughly, commit often, ask questions.**
