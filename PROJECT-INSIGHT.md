# Parkable — Complete Project Insight

> A ground-up explanation of the whole system: what every piece is, why it was
> chosen, how the pieces fit together, what it cost, and what real-world testing
> taught us. Written to be readable with **zero prior knowledge** of the stack.
>
> Companion docs: [`CLAUDE.md`](CLAUDE.md) (build guide), [`AGENTS.md`](AGENTS.md)
> (team rules), [`PROGRESS.md`](PROGRESS.md) (dated decision log),
> [`QUESTIONS.md`](QUESTIONS.md) (interview Q&A), [`docs/schema.md`](docs/schema.md)
> (canonical rule schema).

---

## Table of contents

1. [What the app does](#1-what-the-app-does)
2. [The central idea: Perception ≠ Decision](#2-the-central-idea-perception--decision)
3. [Where to look in the code](#3-where-to-look-in-the-code)
4. [The rules engine](#4-the-rules-engine)
5. [The schema](#5-the-schema)
6. [The extraction pipeline](#6-the-extraction-pipeline)
7. [Databases, Postgres, and PostGIS](#7-databases-postgres-and-postgis)
8. [AWS, service by service](#8-aws-service-by-service)
9. [Government data ETL](#9-government-data-etl)
10. [The mobile app](#10-the-mobile-app)
11. [CI/CD and deployment](#11-cicd-and-deployment)
12. [How time works in this system](#12-how-time-works-in-this-system)
13. [How the tests work](#13-how-the-tests-work)
14. [End-to-end request flow](#14-end-to-end-request-flow)
15. [War stories: bugs real streets found](#15-war-stories-bugs-real-streets-found)
16. [The temporal problem list](#16-the-temporal-problem-list)
17. [Costs, with real numbers](#17-costs-with-real-numbers)
18. [Known limitations](#18-known-limitations)
19. [Glossary](#19-glossary)

---

## 1. What the app does

You are standing next to your car. There is a metal sign on a pole:

```
    2 HOUR PARKING
    MON - FRI
    8 AM - 5 PM
    EXCEPT HOLIDAYS
```

**The question: can I park here, right now, and until when?**

The app answers two ways:

1. **Photograph the sign** — the app reads it and answers.
2. **Just open the app** — it uses GPS to check whether it already knows about
   signs at that spot (from an earlier scan, or from government open data) and
   answers instantly with no photo at all.

The answer is never a bare yes/no. It is:

- a **verdict** — `PARKABLE` / `NOT_PARKABLE` / `DEPENDS`
- a **reason** — "Time limit of 120 minutes applies: 2 Hour Parking Mon–Fri 8AM–5PM"
- a **countdown** — "Move your car by 4:37 PM"
- a **trace** — the step-by-step logic, so the answer can be checked

Getting it wrong costs a real $80 ticket or a tow. That constraint drives every
design decision below, and it produces the project's guiding rule:

> **A confidently wrong "YES" is worse than an honest "I'm not sure — retake the photo."**

---

## 2. The central idea: Perception ≠ Decision

This is the heart of the project. Everything else is plumbing.

### The naive design (deliberately not built)

```
Photo → send to an AI → "can I park here?" → AI says "Yes!" → show the user
```

Why that fails:

- AI models make things up. A confident wrong "yes" is a ticket.
- **It cannot be tested.** Same photo, different answer tomorrow.
- **It cannot be explained.** "Why?" → "the AI said so."
- Switching model providers might break everything, silently.

### What was actually built

```
      PERCEPTION (the AI's only job)          DECISION (plain Java's job)

Photo ──► AI transcribes the sign ──► JSON ──► Java computes ──► Verdict
          like a typist, not a judge   data     the answer        + reason
          probabilistic                         deterministic      + countdown
          untestable                            100% unit-tested   + trace
```

The AI is **only allowed to be a typist**. Its instruction is *"look at this
photo, type out what the sign says, in this exact structured format."* It
produces:

```json
{
  "type": "time_limit",
  "restriction": { "duration_minutes": 120 },
  "time_windows": [ { "start_time": "08:00", "end_time": "17:00" } ],
  "day_pattern": { "type": "specific_days",
                   "days_of_week": ["MON","TUE","WED","THU","FRI"] },
  "exceptions": [ { "exception_type": "holiday_suspension" } ]
}
```

It never says "you can park." Then ordinary Java code takes that JSON plus the
current time and computes the verdict with if/else logic — no AI involved.

### Why this matters

| | AI decides | Java decides (what we built) |
|---|---|---|
| Same input → same output? | No | **Yes, always** |
| Can you write tests? | Not really | **Yes — 251 of them** |
| Can you explain the answer? | No | **Yes — full trace** |
| Switch AI providers? | Scary rewrite | **One config line** |
| Wrong answer means | mystery | **a findable, fixable bug** |

**The proof it works:** the vision provider changed four times during the project
(Claude → GPT-4o-mini → Claude Sonnet → Gemini Flash Lite) and the decision code
never changed a single line.

### The four architecture rules this produces

1. **Lambda handlers contain zero business logic** — the core runs outside AWS unchanged.
2. **`SignSource` + `VisionExtractor` are interfaces from day one** — adding a source or provider must not touch the engine.
3. **The rules engine takes the evaluation instant as a parameter** — never hard-coded "now".
4. **Every stored rule carries a source + version tag** — cache hits require a current `parser_version`.

---

## 3. Where to look in the code

Read them in this order. This is the shortest path to understanding the system.

### Tier 1 — read these first (the core thesis)

| # | File | Why it matters |
|---|---|---|
| 1 | [`model/Rule.java`](backend/src/main/java/com/parkable/model/Rule.java) | 44 lines. The `sealed interface` — only 4 kinds of rule exist. Also holds `describesSameRegulation`, the answer to the dedup saga. Start here. |
| 2 | [`engine/RulesEngine.java`](backend/src/main/java/com/parkable/engine/RulesEngine.java) | ~170 lines. **The heart.** Most-restrictive-wins, side-of-street conflict resolution, time-limit expiry. Every comment explains a real decision. |
| 3 | [`engine/TemporalRuleEvaluator.java`](backend/src/main/java/com/parkable/engine/TemporalRuleEvaluator.java) | ~190 lines. **The hardest code in the project.** Midnight crossing, next-boundary search, DST. |
| 4 | [`docs/schema.md`](docs/schema.md) | The contract everything else obeys. Skim the top 200 lines and the validation rules. |

### Tier 2 — the safety layer

| # | File | Why it matters |
|---|---|---|
| 5 | [`extraction/ValidatingRetryingVisionExtractor.java`](backend/src/main/java/com/parkable/extraction/ValidatingRetryingVisionExtractor.java) | 77 lines. The Decorator pattern doing real work: validate → retry once → honest failure. |
| 6 | [`validation/SemanticRuleValidator.java`](backend/src/main/java/com/parkable/validation/SemanticRuleValidator.java) | The confidence gate (0.7) and the dropped-hours backstop. Both exist because of real bugs. |
| 7 | [`extraction/VisionExtractor.java`](backend/src/main/java/com/parkable/extraction/VisionExtractor.java) | One method. That one method is why swapping providers is free. |

### Tier 3 — the API and storage

| # | File | Why it matters |
|---|---|---|
| 8 | [`lambda/CheckHandler.java`](backend/src/main/java/com/parkable/lambda/CheckHandler.java) | Proof that "handlers have zero business logic" is real. Parse → look up → delegate → format. |
| 9 | [`lambda/ScanHandler.java`](backend/src/main/java/com/parkable/lambda/ScanHandler.java) | The full write path, plus the CORS/`RuntimeException` story in the catch block. |
| 10 | [`repository/postgres/PostgresRuleRepository.java`](backend/src/main/java/com/parkable/repository/postgres/PostgresRuleRepository.java) | All the PostGIS SQL, credential redaction, the pgbouncer fix, batch writes. |
| 11 | [`backend/sql/schema.sql`](backend/sql/schema.sql) | 30 lines. The entire database. |
| 12 | [`lambda/config/StorageStack.java`](backend/src/main/java/com/parkable/lambda/config/StorageStack.java) | The composition root — hand-rolled DI, no Spring. |

### Tier 4 — infrastructure and data

| # | File | Why it matters |
|---|---|---|
| 13 | [`infra/template.yaml`](infra/template.yaml) | The **entire backend infrastructure** in 237 lines. Read the `OPENROUTER_MODEL` comment — it's the model-cost history in prose. |
| 14 | [`datasource/NycSignMapper.java`](backend/src/main/java/com/parkable/datasource/NycSignMapper.java) | The wrong-dataset bug, State Plane coordinate conversion, and the `EXCEPT` fix all live here. |
| 15 | [`datasource/SocrataGovDataFeed.java`](backend/src/main/java/com/parkable/datasource/SocrataGovDataFeed.java) | Lazy pagination — how you stream 440k rows without exploding. |

### Tier 5 — tests worth reading as documentation

| # | File | Why it matters |
|---|---|---|
| 16 | [`engine/TemporalRuleEvaluatorTest.java`](backend/src/test/java/com/parkable/engine/TemporalRuleEvaluatorTest.java) | The `MidnightCrossing` and DST nested classes are the spec for the hardest logic. |
| 17 | [`lambda/CheckHandlerTest.java`](backend/src/test/java/com/parkable/lambda/CheckHandlerTest.java) | See a whole database faked in one line: `(lat, lng, radius) -> stored`. |

### Tier 6 — mobile

| # | File | Why it matters |
|---|---|---|
| 18 | [`mobile/services/api.ts`](mobile/services/api.ts) | Every backend call in one file. The clearest statement of the API contract. |
| 19 | [`mobile/components/ParkingMap.types.ts`](mobile/components/ParkingMap.types.ts) | Shared viewport maths so the native and web maps can't drift apart. |
| 20 | [`mobile/components/VerdictSummary.tsx`](mobile/components/VerdictSummary.tsx) | Shared so Check and Scan can never show different things for the same verdict. |

**If you only read three files:** `Rule.java`, `RulesEngine.java`,
`TemporalRuleEvaluator.java`. That is the project.

---

## 4. The rules engine

Two layers.

### Layer 1 — `TemporalRuleEvaluator`: is *this one* rule active?

Given one rule and one moment, it answers:

1. Is this rule in effect right now?
2. **When does that answer next change?** (this powers the countdown)

#### Midnight crossing — the hardest single case

Sign: `NO PARKING · MONDAY · 11 PM – 2 AM`. That 2 AM is **Tuesday morning**.

The naive check `todayIsInDays(now) && now >= start && now <= end` fails twice:

- **Monday 11:30 PM** — `23:30 <= 02:00` is false (23:30 is the bigger number). Reports inactive. Wrong.
- **Tuesday 1:00 AM** — "is today Monday?" is false. Reports inactive. **Wrong, and you get towed.**

The fix rests on one rule:

> **A midnight-crossing window belongs to the day it STARTS on.**

So "Monday 11 PM – 2 AM" is *one Monday-owned block* that spills past midnight —
not "some Monday plus some Tuesday". There are therefore **two** ways to be inside it:

```
    MONDAY                             TUESDAY
    ────────────────────────┬───────────────────────────
              23:00 ████████│████ 02:00
              └── HEAD ─────┤── TAIL ──┘
              (today is     │  (YESTERDAY was
               Monday,      │   Monday, and it's
               past 23:00)  │   before 02:00)
                        midnight
```

```java
for (TimeWindow window : meta.timeWindows()) {
    if (window.crossesMidnight()) {
        // HEAD: today matches, and we're past the start
        if (dayApplies(meta, day) && !time.isBefore(window.start())) {
            return Path.WINDOW_TODAY;
        }
        // TAIL: YESTERDAY matched, and we're before the end
        if (dayApplies(meta, day.minusDays(1)) && time.isBefore(window.end())) {
            return Path.YESTERDAY_TAIL;
        }
    } else if (dayApplies(meta, day) && window.contains(time)) {
        return Path.WINDOW_TODAY;
    }
}
```

`day.minusDays(1)` is the whole trick. The two branches are checked
**separately, never merged** — which is what stops Tuesday 11:30 PM from
false-firing (head: today isn't Monday ✗; tail: 23:30 isn't before 02:00 ✗).

| Moment | HEAD | TAIL | Result |
|---|---|---|---|
| Mon 23:30 | today=Mon ✓, 23:30 ≥ 23:00 ✓ | — | **ACTIVE** ✓ |
| Tue 01:00 | today=Tue ✗ | yesterday=Mon ✓, 01:00 < 02:00 ✓ | **ACTIVE** ✓ |
| Tue 03:00 | today=Tue ✗ | yesterday=Mon ✓, 03:00 < 02:00 ✗ | inactive ✓ |
| Tue 23:30 | today=Tue ✗ | yesterday=Mon ✓, 23:30 < 02:00 ✗ | inactive ✓ |

#### The next-boundary search

To show a countdown you must find the next moment the answer flips. The code
walks forward day by day collecting candidates (each window's start and end),
with three refinements:

- **Starts at yesterday** (`i = -1`), because a midnight-crossing window that
  began yesterday can still end today. Standing at Tuesday 1 AM, the 2 AM end
  was generated by *Monday's* window — start at today and you never find it.
- **Capped at 366 days.** One leap year guarantees any annually recurring
  pattern (nth-weekday, seasonal) is found. Beyond that, honestly report
  "no known boundary" rather than loop forever on degenerate input.
- **Rejects non-boundaries.** If rule A ends at 17:00 and B starts at 17:00,
  nothing changes at 17:00. `better()` discards any candidate where
  `isActiveAt(candidate) == activeNow`. Without this the app flashes
  "situation changes in 3 minutes!" and then nothing happens.

There is also an early `break`: once a candidate day's midnight is later than
the best found so far, no better candidate can exist.

#### DST

Every zoned instant goes through `ZonedDateTime.of(...)`, so the JDK's uniform
resolution applies — spring-forward gap times shift forward by the gap,
fall-back ambiguous times take the earlier offset. **Tests pin both edges**, so
a JDK or tzdata behaviour change surfaces as a test failure rather than a wrong
verdict at 2 AM in March.

### Layer 2 — `RulesEngine`: several rules on one pole

**Most-restrictive-wins.** Only **active** rules are scored:

```
1. Take all rules on the sign
2. Ask the evaluator: active right now?
3. Discard every inactive rule
4. Score what's left
5. Highest score wins
```

| Rule type | Score | Verdict |
|---|---|---|
| `NoParkingRule` | **3** | `NOT_PARKABLE` |
| `PermitRule` | **2** | `DEPENDS` |
| `TimeLimitRule` | **1** | `PARKABLE` |
| `InformationalRule` | **0** | `PARKABLE` |

The scale is *"how much does this reduce my options?"* — a total ban outranks a
conditional permit, which outranks a clock, which outranks something that
doesn't restrict the space at all.

Worked example, **Wednesday 2 PM**:

| Panel | Active? | Score |
|---|---|---|
| "2 HOUR PARKING MON–FRI 8AM–6PM" | ✅ | 1 |
| "NO PARKING TUESDAY 9–11 AM" | ❌ wrong day | *excluded* |
| "NO DOUBLE PARKING" | ✅ | 0 |

Highest = 1 → **PARKABLE**, countdown to 4 PM. Same sign on **Tuesday 10 AM**:
the no-parking panel is now active, scores 3, and correctly wins → **NOT_PARKABLE**.

Three further details:

- **`PermitRule` → `DEPENDS`** because the engine cannot verify you hold a
  permit. That is honest uncertainty encoded as a verdict.
- **Ties keep the first rule** — `>` not `>=`, so traces are byte-stable across runs.
- **Score 0 still answers.** A pole with only "NO DOUBLE PARKING" wins a
  one-item list and correctly returns `PARKABLE`, with the reason
  *"Does not restrict this space."*

**Directional conflict.** If one active rule applies LEFT and another RIGHT and
the caller didn't say which side the car is on, the naive answer is `DEPENDS`.
Instead the engine computes the verdict **per side** and only returns `DEPENDS`
if they actually differ — two rules on opposite sides that agree must not
manufacture false ambiguity.

**`validUntil` spans all rules, active or not** — "when could the answer change"
includes an inactive rule about to activate.

**The time-limit refinement** (found by real-world testing): a 2-hour limit
inside an 8:30–17:30 window, parking at 15:00, means move by **17:00**
(now + 2h), not 17:30 (the window boundary). The engine takes
`min(rule boundary, now + limit)`.

### Why `sealed` matters

```java
public sealed interface Rule permits NoParkingRule, TimeLimitRule, PermitRule, InformationalRule
```

`sealed` means *"exactly these four kinds exist."* Every `switch` over rule types
is checked for exhaustiveness at compile time. When a fifth type was needed
(`InformationalRule`, for "NO DOUBLE PARKING"), **the compiler refused to build**
until every switch answered "what does this new kind mean here?" Without it, the
code would have compiled and silently done the wrong thing in three places.

The compiler acted as a checklist. It doesn't tell you the right answer — it
guarantees you can't forget to give one.

### Holidays

`UsFederalHolidayCalendar` is **ours** (~70 lines) — there is no holiday calendar
in the Java standard library. Java contributes one building block,
`TemporalAdjusters`, for "3rd Monday in January"-style date arithmetic.

Three parts:

1. **Fixed dates** — New Year's Day, July 4th, Christmas.
2. **Floating** — MLK (3rd Mon Jan), Memorial (last Mon May), Labor (1st Mon Sep), Thanksgiving (4th Thu Nov). Memorial Day uses *last*, not "5th", because May sometimes has four Mondays.
3. **Observation shifting** — a Saturday holiday is observed the Friday before, a Sunday holiday the Monday after. Parking enforcement follows the *observed* day.

The subtle bit:

```java
// New Year's Day falling on a Saturday is observed on Dec 31 of the
// PREVIOUS year, so next year's fixed holidays must be checked too.
for (int year : new int[]{date.getYear(), date.getYear() + 1}) {
```

Asking "is Dec 31, 2026 a holiday?" requires checking **2027's** list and
shifting it backwards.

**Scope, stated honestly:** 7 of the 11 US federal holidays. Veterans Day,
Columbus Day, Juneteenth and Presidents' Day are missing. Documented in the
class, additive to extend, and behind the `HolidayCalendar` interface — so
another country is a new class, not a change. Most tests pass `date -> false`,
a one-line "no holidays" calendar, isolating temporal logic from holiday logic.

---

## 5. The schema

[`docs/schema.md`](docs/schema.md) — ~690 lines defining the exact shape of a
parking rule. Written **before** the ETL code.

**Why it exists:** there are two completely different sources of rules — AI-read
photos, and government open data. NYC publishes messy free text. SF publishes
neat categories. Seattle publishes map segments. LA publishes meter records.
They agree on nothing.

```
NYC data      ──┐
SF data       ──┤
LA data       ──┼──► ONE SCHEMA ──► ONE rules engine
Seattle data  ──┤
Photo scans   ──┘
```

Every source normalises into the same shape, so the engine never knows where
anything came from. **That is why adding 5 cities in Phase 2.5 required zero
changes to the API handlers.**

Structure: an `ExtractionEnvelope` (extraction id, source, parser version,
timestamp, confidence, raw OCR text) containing `rules[]`, each with a type,
time windows, day pattern, exceptions, direction, location and metadata.

**One convention with teeth:** *empty `time_windows` means the rule applies at
ALL hours.* Reasonable — a "NO PARKING ANY TIME" sign genuinely has no hours.
But it makes "empty" a **strong claim**, not "I don't know". That caused a real
safety bug (§15.3).

---

## 6. The extraction pipeline

`VisionExtractor` is one method:

```java
ExtractionResult extract(ImageInput image);
```

`ExtractionResult` is sealed: `Success(envelope, rawJson, metadata)` |
`NeedsReview(message, details, metadata)`.

Implementations:

- **`OpenRouterVisionExtractor`** — raw `java.net.http.HttpClient` against
  OpenRouter's OpenAI-compatible endpoint (no official SDK exists). **Production default.**
- **`ClaudeVisionExtractor`** — the official `anthropic-java` SDK.
- **`FixtureVisionExtractor`** — reads canned JSON from disk, so the whole
  pipeline is testable with zero network and zero cost.

`ExtractionPrompt`, `ExtractionSchema` and `ExtractionResponseParser` were
**extracted into shared classes** so both live providers use byte-identical
prompts and parsing — otherwise providers drift and comparisons become meaningless.

### The Decorator

`ValidatingRetryingVisionExtractor` wraps *any* extractor:

```
extract → schema-validate (raw JSON) → semantic-validate (DTO) → Success
              ↓ fail
        retry once → still fail → NeedsReview("retake with the full sign visible")
```

Validation lives in the **decorator, not the extractors**, so adding a provider
needs zero validation code (Open/Closed). Two stages, in that order, because
semantic checks assume schema-guaranteed shape.

`SemanticRuleValidator` does what JSON Schema can't:

- **Confidence gate** — reject below `0.7`; the model itself is saying it's unsure.
- **Dropped-hours backstop** — reject if `time_windows` is empty *while* the
  sign text contains a clock time (`\d{1,2}(:\d{2})?\s*(AM|PM)`). Scoped to
  single-rule signs so rule A's hours can't false-flag rule B.
- `start == end` is ambiguous — refuse to guess.
- `crosses_midnight` must agree with the start/end ordering; disagreement means
  the model misread the sign.
- `sunset_date` must not precede `effective_date`; `rule_id`s must be unique.

### About cost and tokens

The credits drained not from *more* tokens but from **expensive** ones:

| Model | per 1M in/out | Relative |
|---|---|---|
| `openai/gpt-4o-mini` | ~$0.15 / $0.60 | baseline — **mis-transcribed real signs** |
| `anthropic/claude-sonnet-5` | $2.00 / $10.00 | ~13× — accurate but expensive |
| `google/gemini-2.5-flash-lite` | $0.10 / $0.40 | **~20× cheaper than Sonnet. Current.** |

The move to Sonnet was a **correctness** decision — gpt-4o-mini mis-transcribed
sign text, and a wrong verdict costs a ticket. But every scan then cost ~13×
more, credits ran out, and the app started failing (see the CORS story, §15.7).

Four things that legitimately drive tokens per scan:

1. **Images are token-heavy** — a photo is chopped into tiles, 1,000–2,000+ tokens. This is why the app resizes and compresses before upload.
2. **Capture quality was deliberately raised** 0.5 → 0.85, because signs were coming out blurry. Bigger image, more tokens — a conscious trade.
3. **Retries double the cost.** A rejected extraction costs 2× and yields nothing. So the safety gates *increase* spend by design: bad photos now cost double instead of silently producing a wrong answer.
4. **The prompt + schema** are sent on every call — a fixed per-scan cost.

One genuinely wasteful bug: while dedup was broken, rescanning left contradictory
rules in the database, which confused `/check`, which made users **rescan again** —
every one a full-price AI call.

The fallback ladder is written into `infra/template.yaml` so nobody re-derives it:
if quality regresses → `gemini-2.5-flash` ($0.30/$2.50) → `claude-haiku-4.5`
($1/$5) → back to `sonnet-5`. **`gpt-4o-mini` is permanently ruled out.**
Pricing was verified against OpenRouter's live pricing API, not from memory,
because catalogs change constantly.

---

## 7. Databases, Postgres, and PostGIS

### The basics

A **database** stores data and finds it again fast — like a spreadsheet that
holds hundreds of millions of rows, serves many programs at once, survives
crashes, and has a precise query language (SQL).

**PostgreSQL** is the most respected free open-source database. The key property
here: it is **extensible** — you can bolt on extra capabilities.

### The two tables

**`rules`** — every parking rule the system knows:

| Column | Type | Holds |
|---|---|---|
| `id` | UUID | Unique identifier |
| `location` | **GEOGRAPHY(Point, 4326)** | Where on Earth |
| `rule` | JSONB | The full rule as JSON |
| `source` | TEXT | `camera_scan` or `gov_data` |
| `parser_version` | TEXT | Which reader version produced it |
| `created_at` | TIMESTAMPTZ | When stored |

**`rule_reports`** — user "🚩 report an issue" submissions. Deliberately **just a
log**: a report can never automatically change or delete a rule, or anyone could
take down a legitimate rule by spamming false reports.

Two design choices:

- **One row per RULE, not per photo.** A 3-panel sign becomes 3 rows. Searching
  is by location; if a row held a whole scan, the database would unpack every
  nearby scan's JSON just to find matching rules.
- **JSONB, not 40 columns.** Rules are wildly variable — some have two time
  windows, some none, some nth-weekday patterns, some holiday exceptions. Fixed
  columns mean either 40 mostly-empty columns or constant migrations.

### PostGIS

**The problem:** find every rule within 25 metres of `lat 37.7749, lng -122.4194`.

Why the obvious approaches fail:

- **Compare the numbers directly** — latitude/longitude are **angles**, not
  distances. One degree of longitude is 111 km at the equator and **0 km at the
  pole**. A fixed number of degrees means a different distance in every city.
- **Fetch everything and compute in Java** — correct, but that's loading
  **250,000 rows** to answer one query, and it gets worse with every import.

**PostGIS is an add-on that teaches Postgres about the Earth.** One line:

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
```

Three pieces are used:

#### ① The `GEOGRAPHY` column type

```sql
location GEOGRAPHY(Point, 4326) NOT NULL
```

**`GEOGRAPHY` vs `GEOMETRY` is the critical choice.** `GEOMETRY` treats
coordinates as flat X/Y on a plane; `GEOGRAPHY` treats them as points on the
curved Earth. With `GEOGRAPHY`, "within 25" means **25 real metres**. With
`GEOMETRY`, it would mean 25 *degrees* — about 2,700 km. Every parking rule on
the continent.

#### ② What `4326` is

**`4326` is an ID for a way of describing positions on Earth.** It has nothing to
do with your phone's hardware.

Here is the surprise: *"latitude 37.7749, longitude -122.4194" is not, by itself,
a location.* It's only a location once you say **which system of measurement**
you're using. Different systems disagree about the Earth's shape and where zero
is — the same numbers can land hundreds of metres apart.

**SRID** = Spatial Reference IDentifier, a registry of numbered coordinate systems:

| SRID | Name | What it is |
|---|---|---|
| **4326** | **WGS84** | Global GPS standard, lat/lng in degrees. **What every phone uses.** |
| 3857 | Web Mercator | The flattened projection map tiles are drawn in |
| 2263 | NY State Plane (feet) | A local flat grid for NYC, in feet |
| 26910 | UTM Zone 10N | A local flat grid for Northern California, in metres |

So `4326` means *"these are ordinary GPS lat/lng degrees on the WGS84 model of
the Earth"* — the model the GPS satellites themselves broadcast against.

`ST_MakePoint` produces a bare pair of numbers with no meaning; `ST_SetSRID(…, 4326)`
stamps them as GPS coordinates so PostGIS knows how to compare them.

**This is not hypothetical.** NYC publishes **State Plane in feet** (SRID 2263) —
a coordinate looks like `(985234.5, 203456.8)`. Stored as 4326, longitude
`985234.5` degrees is meaningless (valid range is −180…180) and every NYC sign
would land nowhere. That is the entire reason **proj4j** is a dependency: the NYC
mapper converts State Plane → WGS84 on import.

#### ③ `ST_DWithin` and the GiST index

```sql
ST_DWithin(location, ST_SetSRID(ST_MakePoint(-122.4194, 37.7749), 4326)::geography, 25)
```

*"Is this location within 25 metres of that point?"* PostGIS does the spherical
geometry.

> **⚠️ The classic trap:** `ST_MakePoint` takes **longitude FIRST, then latitude** —
> it's `(x, y)`, and x is east-west. Humans always *say* "latitude, longitude".
> Swap them and every location silently lands elsewhere on Earth. It never
> crashes; it just gives wrong answers. **Every binding site in this codebase
> carries a comment about it.**

```sql
CREATE INDEX rules_location_gist_idx ON rules USING GIST (location);
```

An **index** is the back of a textbook — without one, finding a topic means
reading all 900 pages. **GiST** is an index type for shapes and locations: it
organises the world into nested boxes (North America → California → San
Francisco → the Mission), so a search only opens boxes that could contain your
point. The difference between touching 250,000 rows and touching a handful.

#### The actual query

```sql
SELECT rule, source, parser_version,
       ST_Y(location::geometry) AS latitude,
       ST_X(location::geometry) AS longitude
FROM rules
WHERE parser_version = ANY (?)
  AND ST_DWithin(location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
ORDER BY ST_Distance(location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
```

`ST_Y`/`ST_X` pull the coordinates back out so the app can say "12 m northeast of
you". `ST_Distance` sorts nearest-first.

#### `parser_version` — cache validity for free

Every rule records which reader version produced it. The lookup only accepts
**currently valid** versions. Improve the prompt → bump the version → every rule
read by the old prompt **stops being trusted automatically** and gets re-scanned.

Cache invalidation with no cleanup job, no manual deletion, no stale data. It's
one `WHERE` clause.

#### Idempotent writes

```sql
ON CONFLICT (id) DO UPDATE SET ...
```

with `stableRuleId = UUID.nameUUIDFromBytes(extractionId + "\n" + ruleId)`. The
same rule always produces the same id, so **re-running an import corrects rows in
place instead of duplicating them.** This turned the `EXCEPT` bug (§15.2) from a
data incident into a re-run.

### Supabase, pgbouncer, and prepared statements

**Supabase** runs Postgres for you — backups, patches, uptime. Free tier, 500 MB,
region **West US (North California)** to sit near the AWS region.

#### Why a connection pooler is needed

Opening a database connection is slow (50–200 ms) and a server allows only a
limited number at once — Supabase's free tier around **60**.

A traditional app opens 10 connections at boot and reuses them for months.
**Lambda doesn't work like that.** Every request may run in a brand-new
container, and a traffic spike starts 100 containers at once:

```
100 requests → 100 containers → 100 connections requested → DB allows 60 → 40 fail
```

This is the classic serverless + traditional-database failure.

**pgbouncer is a receptionist in front of the database.** It accepts many cheap
client connections but keeps a small pool of real ones and shares them around.
Supabase exposes it on **port 6543** (port 5432 is direct). This project uses
**6543**, which is correct.

#### The catch, and the one-line fix

pgbouncer runs in **transaction mode**: you hold a real connection only for the
duration of one transaction, after which it may go to a completely different
client. So **two statements in a row may run on two different physical connections.**

Separately, the Java Postgres driver has an optimisation: after the same
statement executes ~5 times, it quietly tells the server *"remember this as S_1"*
so it doesn't re-parse it each time. Put the two together:

```
"Remember this query as S_1"  → routed to real connection #3
[transaction ends, #3 returns to the pool]
"Run S_1"                     → routed to real connection #7, which never heard of S_1
```

or the mirror image, which is the error actually hit:
`ERROR: prepared statement "S_1" already exists`.

```java
properties.setProperty("prepareThreshold", "0");   // never name statements server-side
```

You give up a small optimisation and gain the ability to use a pooler at all.
This is the documented fix for JDBC behind pgbouncer.

**How it was found:** normal traffic never triggered it — one scan runs each
statement once, nowhere near the threshold. Then the **bulk city import** ran,
where `saveAll()` reuses one prepared statement thousands of times
(`BATCH_FLUSH_SIZE = 500`), crossing the threshold on the first flush. The
importer was the only code path in the project that could have found this.

> Two good decisions collided: the batching exists because opening a fresh
> connection per record would exhaust the connection limit long before write
> throughput became the bottleneck.

#### Two more real Supabase problems

- **The connection string.** Supabase hands out
  `postgresql://user:password@host` — and the Java driver **cannot parse it**,
  treating the whole `user:password@host` as one hostname. A regex splits them
  out. The password group is greedy, so a password containing `@` still splits
  on the *last* `@`, matching how libpq behaves.
- **Credential redaction.** The driver bakes the full connection string,
  password included, into its own error messages, which land in CloudWatch.
  Connection failures are caught and rethrown with the URL redacted — and
  **deliberately without chaining the original exception**, because any
  stack-trace printer would render its message and leak the password anyway.

---

## 8. AWS, service by service

### What "the cloud" means

The Java code must run somewhere, 24/7, reachable worldwide. Options: your laptop
(dies when closed, unreachable), buying a server (thousands of dollars plus
sysadmin work), or renting compute from Amazon for pennies. **AWS is option 3** —
about 200 services, of which this project uses **seven**.

### 8.1 Lambda — where the Java runs

**Old way (a server):** rent a computer running 24/7 waiting for requests. You
pay for all 24 hours including 4 AM when nobody's using it, and you patch the OS.

**Lambda (serverless):** upload just the code. Amazon runs it **only when a
request arrives**, then shuts it down. You pay per request and per millisecond.
For a small app that's the difference between ~$10/month and ~$0.00.

You provide: the code (one ~30 MB shaded jar), the entry point
(`com.parkable.lambda.CheckHandler::handleRequest`), memory (**1024 MB**), and a
timeout (**30 s**).

| Lambda | Route | Job |
|---|---|---|
| `parkable-check` | `GET /check` | Answer from stored rules near a GPS point |
| `parkable-nearby` | `GET /nearby` | List nearby rules with distance + bearing |
| `parkable-scan` | `POST /scan` | Photo → AI → store → verdict |
| `parkable-report` | `POST /report` | Log a complaint about a rule |
| `parkable-reports` | `GET /reports` | Admin review, shared-secret gated |

All five run from the **same jar**, just different entry points.

**Cold starts.** Because Lambda shuts down when idle, the first request after a
quiet period boots a container and starts the JVM — **1–3 seconds**. Later
requests reuse the warm container.

No SnapStart or provisioned concurrency was configured, deliberately: the common
path (`/check`) is a database read, fast even cold; and the slow path (`/scan`)
already waits several seconds on the AI, so a cold start is lost in the noise.
`MemorySize: 1024` isn't about memory — on Lambda **more memory means
proportionally more CPU**, so it's there to speed JVM startup.

**Two constructors per handler:** a public no-arg one (what Lambda actually
invokes, wiring from env vars) and a dependency-injecting one (what tests use).
That is the entire reason handlers are testable without AWS.

**Cost: $0.00** — free tier covers 1M requests/month.

### 8.2 API Gateway — the front door

Lambda functions aren't websites. API Gateway is the managed front door: it holds
the public HTTPS address, terminates encryption, and routes URLs to functions.

```
https://xxxxx.execute-api.us-west-1.amazonaws.com/Prod
```

It also handles **CORS** — browsers block a page on site A from calling an API on
site B unless B says it's allowed. The web build runs on Vercel and calls AWS, so:

```yaml
Cors:
  AllowOrigin: "'*'"
  AllowHeaders: "'Content-Type'"
  AllowMethods: "'GET,POST,OPTIONS'"
```

**Cost in July: $0.0007.**

### 8.3 S3 — photo storage

An effectively infinite hard drive on the internet: store a file under a key, get
it back later.

Photos are kept for three reasons: showing you the sign a verdict came from;
**reprocessing** (when extraction improves, re-read old photos without re-walking
the street); and debugging wrong verdicts. They don't go in the database —
databases are bad at large binaries and expensive per GB.

The bucket `parkable-photos` is **fully locked down**:

```yaml
PublicAccessBlockConfiguration:
  BlockPublicAcls: true
  BlockPublicPolicy: true
  IgnorePublicAcls: true
  RestrictPublicBuckets: true
```

**So how does the app show a photo? Presigned URLs.** A presigned URL is a
temporary, cryptographically signed link to **one specific file**:

```
https://parkable-photos.s3.us-west-1.amazonaws.com/scan/8f3a1c2e-...
   ?X-Amz-Expires=3600&X-Amz-Signature=a1b2c3d4...
```

The signature proves the Lambda (which has permission) authorised reading that
one file until that time. S3 checks it and serves the image. This is how a
private bucket displays a picture without ever being public.

**Why 1 hour and not S3's 7-day maximum:** inside Lambda, presigning uses the
execution role's own **temporary, auto-rotating credentials**. A 7-day URL would
stop working when those rotate — a promise the system can't keep.

**Cost in July: $0.0053.**

### 8.4 SSM Parameter Store — secrets

Three secrets must never be in the code (the code is on GitHub):

```
/parkable/db-url
/parkable/openrouter-api-key
/parkable/admin-secret
```

**They are injected at deploy time as environment variables.** The Java code just
calls `System.getenv()` and **never talks to AWS** — which is exactly why the
same code runs identically on a laptop. `EnvConfig.java` is the whole of it.

Note what is *not* secret: the model name `google/gemini-2.5-flash-lite` sits as
a plain literal in the template with a comment saying why — it's an identifier,
not a credential.

**A Windows war story:** `aws ssm put-parameter --name "/parkable/admin-secret"`
failed with *"Parameter name must be a fully qualified name"*. Git Bash's MSYS
layer assumes anything starting with `/` is a file path and silently rewrote it
into a Windows path. Fix: prefix with `MSYS_NO_PATHCONV=1`.

**Cost: $0.00.**

### 8.5 CloudFormation + SAM — infrastructure as code

Clicking 5 Lambdas, an API Gateway, a bucket and a pile of permissions together
in a web console leaves no record and no way to rebuild. **CloudFormation** takes
a file describing what you want and builds it; change the file and it applies
only the difference. **SAM** is shorthand on top for serverless apps.

[`infra/template.yaml`](infra/template.yaml) — **237 lines defining the entire
backend.** If the AWS account vanished, one command rebuilds it. And the
infrastructure is version-controlled next to the code, so the model-change
history (and its reasoning) is in git.

**Cost: $0.00.**

### 8.6 IAM — permissions

Least privilege. Each Lambda gets exactly what it needs:

```yaml
- ssm:GetParameter        # read my secrets
- s3:PutObject/GetObject  # only the parkable-photos bucket
```

`parkable-report` gets no S3 permission at all — it doesn't touch photos.

Human side: MFA on the root account, then a day-to-day `parkable-dev` user, with
root never used again. CI got a **separate** access key so a leak there can be
revoked without breaking local work.

**Cost: $0.00.**

### 8.7 CloudWatch — logs

Anything printed inside Lambda lands here. It's how the OpenRouter-out-of-credits
failure was diagnosed: the user saw a generic message, the real reason was in the
logs.

**Cost: $0.00** — free tier covers 5 GB.

---

## 9. Government data ETL

**ETL** = Extract, Transform, Load. If a city already publishes its parking
regulations, no photo and no AI call are needed — `/check` answers in under a
second for **$0**.

### Two adapters, not five pipelines

Transport (how to download) is split from semantics (what the fields mean):

```
TRANSPORT (2 classes)              MEANING (5 classes)
├── SocrataGovDataFeed   ────►     ├── NycSignMapper
│   (NYC, SF, LA, Chicago)         ├── SfSignMapper
└── ArcGisGovDataFeed    ────►     ├── LaSignMapper
    (Seattle)                      ├── ChicagoSignMapper
                                   └── SeattleSignMapper
```

Socrata and ArcGIS are the two platforms most US cities publish on. A new city on
either platform needs **only a mapper**.

This is deliberately distinct from Phase 1's `SignSource`: `GovDataFeed` is
**bulk enumeration**, `SignSource` is **point query**. Different problems,
different interfaces.

### Real-world nastiness handled

- **Streaming.** NYC has 440,656 rows; loading that at once would blow up. The
  feed fetches 1,000 rows at a time, lazily, via a custom `Iterator<JsonNode>`.
- **Coordinate conversion.** NYC's State Plane feet → WGS84 via proj4j (§7).
- **Free-text parsing.** `"NO PARKING 8AM-6PM MONDAY THURSDAY"` regex-parsed for
  hours, day names, and `ANY TIME`.
- **Batched writes.** One connection and one prepared statement reused,
  flushing every 500 — a fresh connection per record saturates the DB's
  connection limit long before write throughput.

### The filtering philosophy

NYC's mapper only accepts text containing `"NO PARK"`. "No Standing" and "No
Stopping" are **silently skipped** rather than guessed at:

> **A coverage gap is safe** (the app says "no data here").
> **A misclassification is unsafe** (the app confidently gives the wrong answer).
> When in doubt, skip.

### Loaded and verified live

| City | Rows |
|---|---|
| New York | **195,121** |
| Seattle | **52,099** |
| San Francisco | **6,759** |

---

## 10. The mobile app

**React Native** — one JavaScript/TypeScript codebase for iOS and Android.
**Expo** — a toolkit on top handling camera, GPS, notifications, and builds
without Xcode or Android Studio; crucially it also **exports to a website**,
which is how the app runs in a browser. **TypeScript** — JavaScript with type
checking, the same benefit Java's strictness gives the backend.

10 screens (Home, Check, Scan, Nearby, History, Favorites, Find My Car, Report,
Admin, Onboarding), 4 bottom tabs, light and dark mode, animated verdict reveal,
haptics.

Shared primitives — `Card`, `IconBadge`, `AppButton`, `VerdictSummary`,
`ScreenContainer`, `ParkingMap` — exist so screens can't drift apart. In
particular `VerdictSummary` is shared so **Check and Scan can never show
different things for the same verdict**.

### Maps: two implementations, one component

`react-native-maps` is a **wrapper**, not a map — it bridges to whatever the OS
provides:

| Platform | Engine |
|---|---|
| **iOS** | **Apple Maps** (MapKit) |
| **Android** | **Google Maps** (the only real option) |

No `provider` prop is set, so both take the defaults — **no Google Maps API key,
no billing account anywhere in this project.**

But `react-native-maps` **has no web renderer at all**. In a browser there is
nothing on the other end of the bridge. So the map is split:

```
ParkingMap.types.ts     ← shared props + viewport maths (both import this)
ParkingMap.native.tsx   ← react-native-maps → Apple / Google Maps
ParkingMap.web.tsx      ← react-leaflet     → OpenStreetMap tiles
```

Metro (React Native's bundler) picks by filename suffix automatically, so
`react-native-maps` is **never even imported into the web bundle** and can't
crash there. The rest of the app just writes `<ParkingMap … />`.

**Leaflet and OpenStreetMap are two different things** — one is the picture
frame, the other is the picture:

| | **Leaflet** | **OpenStreetMap** |
|---|---|---|
| What | A JavaScript **library** | A map **dataset** + free tile servers |
| Who | Open-source project | Non-profit + ~10M volunteer mappers |
| Does | Draws the map, pan/zoom/pins | Provides the actual imagery |
| Analogy | The video player | The video file |

Leaflet has no map data of its own; OpenStreetMap isn't code. Two lines join them:

```tsx
<TileLayer
  attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
  url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
/>
```

A world map isn't one image — it's **256×256 pixel tiles** at every zoom level.
`{z}` is zoom (0 = planet, 19 = buildings), `{x}`/`{y}` are the grid position,
`{s}` spreads load across servers. Leaflet computes which tiles it needs and lays
them out; OpenStreetMap serves them. The `attribution` line is a **legal
requirement** of the licence, not decoration.

`react-leaflet` is a thin React wrapper so Leaflet can be written as JSX.

**Cost:** Leaflet $0, OpenStreetMap $0 (attribution required), Apple Maps $0,
Google Maps on Android $0 at this usage. The Google Maps *JavaScript* API would
have needed a billing account — choosing Leaflet + OSM for web is why there's no
Google key in this repo.

### Real problems solved

- **The camera wouldn't work on a real device.** Expo's `CameraView` threw a bare
  `"Failed to capture image"`. The string was traced into Expo's own Android
  source to confirm it's a generic CameraX error, not decodable further from JS.
  Two timing theories were tested and disproved. **Final fix: drop the in-app
  camera for `expo-image-picker`'s `launchCameraAsync()`**, which delegates to
  the phone's stock camera app. Worked immediately.
  *Sometimes the right answer is to stop owning the hard part.*
- **GPS drift.** Grouping signs by exact coordinates (`toFixed(5)`, ~1 m) split
  re-scans of one sign into separate entries, because phone GPS wanders 5–15 m.
  Fixed with 15 m proximity clustering **for display** — note the *destructive*
  side needed content matching instead (§15.5). Same problem, opposite correct answers.
- **The camera mirror trap.** Expo CSS-mirrors the *preview* on desktop webcams,
  but `takePicture` never mirrors the saved image — verified by reading Expo's
  source. "Fixing" the upload would have flipped every direction arrow and
  **introduced** a bug while fixing a cosmetic one. Only the preview needed correcting.
- **"Start Parking Timer."** Scan time ≠ parking time — you still walk back to
  the car. Instead of starting a countdown at scan time, a button re-calls
  `/check` at the moment you press it. No backend change; the existing endpoint
  just anchored to a later instant.
- **Photo resize/compress before upload**, to survive slow connections (and cut tokens).
- **`ScreenContainer`.** Screens were a plain `flex: 1` View with
  `justifyContent: 'center'`, which silently **clips**: a tall verdict (photo +
  timer + trace + buttons) overflowed both ends at once, so the top of the reason
  text *and* the buttons were unreachable with no scrollbar to hint at it.
  `flexGrow` on the content container keeps centring for short content without
  buying that.

### One honest gap: History thumbnails

Presigned URLs are generated **fresh on every request**, so `/check`, `/nearby`
and `/scan` display photos of any age — the URL is never stored, only the S3 key
is. **However**, the local Scan History screen saves the whole response
(including the URL string) to AsyncStorage and re-renders it later. An hour on,
that signature has expired and the thumbnail goes blank.

| Screen | Photo | Why |
|---|---|---|
| Check / Nearby / fresh Scan | ✅ always works | Fresh URL signed per request |
| **Scan History (>1h old)** | ❌ blank | Saved URL string has expired |

The fix (not built) is for History to store the `photo_reference` and ask the
backend for a fresh URL at render time.

---

## 11. CI/CD and deployment

- **`.github/workflows/backend.yml`** — every push/PR touching `backend/**` or
  `infra/**` runs the full test suite (~36 s). **Deploy is `workflow_dispatch`
  only** — manual trigger from the Actions tab or `gh workflow run`.
- **`.github/workflows/mobile.yml`** — every push touching `mobile/**` runs
  `npm run typecheck` and `npx expo export --platform web`.

**Why deploy is manual:** the original design used a GitHub Environment with a
required reviewer, which returned a 422 — required-reviewer protection needs a
**paid plan on private repos**. `workflow_dispatch` gives the same "nothing
reaches AWS without a deliberate action" guarantee for $0.

Other real details:

- A **dedicated IAM key** was minted for CI rather than reusing the local one,
  and piped straight into `gh secret set` without ever being echoed.
- The repo came up **public** by default when created through GitHub's web UI,
  despite "private" being the decision. Caught and flipped immediately.
- The full git history was scanned for committed secrets before the first push —
  clean (only test fixtures like `sk-ant-test`).
- **Cost was measured before re-architecting.** A worry that automated deploys
  would raise the AWS bill was checked against Cost Explorer: deploy frequency
  doesn't affect cost at all, only request volume does. Moving off Lambda would
  have been a real re-architecture motivated by an imaginary problem.

---

## 12. How time works in this system

**Exactly one line reads the real clock**, at the request edge:

```java
Instant at = QueryParams.optionalInstant(params.get("at"), "at").orElseGet(clock::instant);
```

*"Did the caller say what time to use? If not, ask the clock."* In production the
clock is `Clock.systemUTC()`. That `Instant` is then **passed as a parameter**
through everything; nothing below the handler ever calls `Instant.now()`.

Three payoffs:

1. **Tests freeze time** — `Clock.fixed(Instant.parse("2026-07-14T18:04:00Z"), UTC)`.
   Without this you literally could not test "11:59 PM on a Monday" except at
   11:59 PM on a Monday.
2. **Production is replayable** — add `&at=2026-07-14T18:04:00Z` to any call and
   get exactly what the app would have said then.
3. **Determinism** — the whole "deterministic engine" claim would be false the
   moment any inner code read the clock.

### Instant vs Zone

| Concept | Type | What it is | Example |
|---|---|---|---|
| **Instant** | `Instant` | An absolute moment; the same everywhere | `2026-07-14T18:04:00Z` |
| **Zone** | `ZoneId` | Rules for turning that into local wall-clock time | `America/Los_Angeles` |

A sign saying "8 AM" means wall-clock time *in the city where the sign is*, so
the engine needs both: `instant.atZone(zone)`.

```java
static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Los_Angeles");
```

**`America/Los_Angeles`, not `PST`** — the former is a *rulebook* that knows about
daylight saving (PST in winter, PDT in summer, and exactly when it flips).
Hardcoding `PST` would be an hour wrong for eight months a year.

**A genuine gap:** the default is LA, so a New York sign checked without a `zone`
parameter is evaluated in Pacific time. The GPS is right there in the request and
could derive the zone — it doesn't. The fix belongs at the handler edge, the one
place already allowed to resolve ambient context.

---

## 13. How the tests work

**JUnit 5** (runner), **AssertJ** (readable assertions), **Mockito** (fakes, used
sparingly). `mvn test` → **251 tests, ~36 seconds, no internet, no AWS, no database.**

A real test:

```java
@Test
void activeMidWindowBoundaryIsWindowEndToday() {
    Rule rule = new RuleBuilder().timeLimit(Duration.ofHours(2))
            .onDays(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
            .duringWindow(LocalTime.of(8, 0), LocalTime.of(18, 0)).build();

    RuleActivation activation = evaluator.evaluate(rule, la(2026, 7, 15, 10, 0));

    assertThat(activation.active()).isTrue();
    assertThat(activation.nextBoundary()).contains(Instant.parse("2026-07-16T01:00:00Z"));
}
```

Every test is **arrange → act → assert**. Test names are full sentences, so a
failure explains itself without reading code. And note the assertion is
`2026-07-16T01:00:00Z` for "6 PM" — 6 PM Pacific *is* 1 AM UTC the next day, so
the test forces the timezone conversion to be right.

Five techniques make it possible:

1. **`RuleBuilder`** — a test-data builder, so a rule reads like the sign itself
   instead of eight lines of nested construction.
2. **Fakes over real dependencies** — a whole database in one line:
   `new CheckHandler((lat, lng, radius) -> stored, FIXED_CLOCK)`. This is the
   payoff of the Repository/port pattern; the interface exists *so that* this line can.
3. **Frozen clocks** (§12).
4. **`FixtureVisionExtractor`** — a fake AI reading canned JSON, so the entire
   scan pipeline is tested end-to-end for **$0 and zero network calls**.
5. **`@Nested`** — grouping, e.g. `MidnightCrossing > stillActiveAt0100Tuesday`.

Rough coverage: temporal ~60, engine ~30, validation ~30, handlers ~35,
repository ~25, gov mappers ~30, extraction ~25, model/misc ~16. Two tests are
env-guarded (they need a real DB, run only when `PARKABLE_DB_URL` is set, and
clean up after themselves).

### The caveat the project learned the hard way

**236 tests were passing while NYC's importer was downloading the wrong dataset
and storing zero rows.** The mapper tests fed it canned fixtures — they correctly
proved "given this record, produce this rule", and could not possibly prove "we
are downloading the right file".

> **Tests verify the logic you wrote. They cannot verify the assumptions you made.**
> Integration points need at least one check against the real thing.

---

## 14. End-to-end request flow

```
┌─────────────────────────────────────────────────────────────────┐
│  PHONE                                                          │
│  1. Tap Scan → the phone's own camera app → shoot                │
│  2. Resize + compress (4 MB → ~300 KB)                          │
│  3. Encode base64 (binary → text, so it fits in JSON)           │
│  4. Read GPS: 37.7749, -122.4194                                │
│  5. POST /scan { photo_base64, media_type, lat, lng }           │
└─────────────────────────────────────────────────────────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  API GATEWAY — terminate HTTPS, match "POST /scan",             │
│  attach CORS headers, invoke → parkable-scan                    │
└─────────────────────────────────────────────────────────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  LAMBDA (1024 MB, 30 s)                                          │
│  Cold start: boot container, start JVM, load jar, run the        │
│  no-arg constructor (reads env vars SSM injected at deploy)      │
└─────────────────────────────────────────────────────────────────┘
        ▼
  6. VALIDATE INPUT — valid JSON? supported media type? decodable
     base64? coordinates in range?  → 400 and stop. No AI call.
        ▼
  7. AI CALL — OpenRouter → gemini-2.5-flash-lite. 2–5 s, ~$0.0002
        ▼
  8. VALIDATE #1 (structural) — JSON Schema: shape, required fields, enums
        ▼
  9. VALIDATE #2 (semantic) — confidence ≥ 0.7? start ≠ end?
     crosses_midnight consistent? hours not dropped?
        ▼
     ┌── FAILED? ──────────────────────────────────┐
     │ Retry the AI once. Still failing → 422       │
     │ "Couldn't read this sign clearly. Retake     │
     │  with the full sign visible and in focus."   │
     │ ← honest uncertainty, STOP                   │
     └──────────────────────────────────────────────┘
        ▼ passed
 10. TO DOMAIN — RuleFactory → NoParking / TimeLimit / Permit / Informational
        ▼
 11. DEDUPLICATE — supersedeMatching(): delete only existing nearby
     camera_scan rules whose CONTENT matches this scan
        ▼
 12. SAVE — one INSERT per rule. ST_MakePoint(lng, lat) ← longitude FIRST.
     JSONB + _parkable provenance. ON CONFLICT DO UPDATE (idempotent)
        ▼
 13. PHOTO → S3 — upload, presign 1 h. Failure here is swallowed:
     a thumbnail must never break a working scan
        ▼
 14. ★ DECIDE — the only moment a verdict exists ★
     RulesEngine.evaluate(rules, now, zone, side)
       → per-rule activation + next boundary
       → drop inactive rules
       → resolve left/right
       → most-restrictive-wins
       → validUntil = min(rule boundary, now + time limit)
     NO AI. Pure Java. Deterministic.
        ▼
 15. RESPOND — { verdict, reason, valid_until, confidence, photo_url, trace }
        ▼
 16. PHONE — animated reveal + haptic, countdown, [Start Parking Timer],
     [Why this verdict?] (the trace), [🚩 Report an issue]

Total ≈ 3–6 s.  Lambda then goes idle and billing stops.
```

**`/check` skips steps 6–13 entirely:** database lookup → engine → answer.
**Under a second, $0, no AI.**

Note the photo takes **one hop** (phone → API Gateway → Lambda), is used twice
(AI, S3), and is gone when the container dies. And **SSM is not in the request
path** — secrets are injected at deploy time, which is why the same code runs on
a laptop.

---

## 15. War stories: bugs real streets found

These are the most valuable part of the project. None would have been caught by
unit testing.

### 15.1 The NYC import was pointed at the wrong dataset — for weeks

The code downloaded Socrata resource `afgb-4qw7`: a **118-row** feed of
*"PRESS NYP LICENSE PLATES ONLY"* signs. The correct one is `nfid-uabd` —
**440,656 rows** of actual parking regulations, named in the project's own schema
doc.

**NYC had contributed exactly zero rows to production while every test passed**,
because the tests used canned fixtures and never exercised a real resource id.

The crucial realisation: **the database cannot answer "what are we missing?"**
Mappers filter *before* storage, so querying stored rows only shows what was
accepted, never what was silently dropped. The only way to know was to read each
city's live raw feed.

Result: **NYC 0 → 195,133 rows.**

> Fixtures verify your parsing. They cannot verify you're parsing the right
> thing. A filtering pipeline must measure what it *rejected*.

### 15.2 The `EXCEPT` bug — a parser that inverted 11,820 rules

Right after that import, `"NO PARKING 6AM-8AM EXCEPT SUNDAY"` was stored as
**`days: Sunday`**. The day-name scanner saw "SUNDAY" — but it was in the
*exception* clause. "Every day **except** Sunday" (Mon–Sat) became "**only**
Sunday". The exact opposite. **~11,820 live NYC rows** contain both "NO PARK" and
"EXCEPT", so this was not an edge case.

Fix: split on `EXCEPT`; day names after it are **exclusions** from a base set
(days named before it, or all seven if none).

And the payoff of an earlier decision: because writes are **idempotent**,
re-running the import **corrected every bad row in place** — 195,121 rows, zero
duplicates. Not a cleanup script. Just a re-run.

> In text parsing, negation is where correctness dies. Idempotent writes turn a
> data disaster into a Tuesday afternoon.

### 15.3 The empty-hours safety bug

A live scan of **"2 HOUR PARKING MONDAY THRU FRIDAY 8AM-5PM"** returned
`description: "2 Hour Parking"`, the hours only in `raw_text`, and
`time_windows: []`.

Per the schema convention, **empty means "applies at ALL hours"** — so the app
would have limited you to 2 hours **at 3 AM**, when parking was actually
unrestricted.

Fixed at **two layers**:

1. **Prompt** — stated hours must populate `time_windows`, never live only in raw text.
2. **Code backstop** — reject when `time_windows` is empty while the rule's own
   text contains a clock time. Scoped to single-rule signs so rule A's hours
   can't false-flag rule B.

You don't just fix the prompt, because **a prompt is not a guarantee**. Parser
versions bumped to `v3`, automatically invalidating every rule read by the old prompt.

### 15.4 The confidence gate that was never implemented

The stated philosophy from day one was *"low confidence → retake, never
confidently wrong."* **Nothing in the code ever read the model's confidence
score.** Validation only checked structural shape, so a blurry photo that
happened to parse into well-formed JSON sailed through as a trusted verdict. A
real blurry scan did exactly that.

Now `MIN_CONFIDENCE = 0.7`.

> A stated principle that isn't an assertion in code is just a comment.

### 15.5 Deduplication — three wrong answers before the right one

| Attempt | Approach | How it broke |
|---|---|---|
| 1 | No dedup | Blurry scan + clear rescan = two contradictory rules |
| 2 | Delete within **25 m** | 25 m was tuned for *answering queries* (generous = harmless). Reused on the **destructive** side, rescanning one sign could wipe a different sign 20 m away |
| 3 | Delete within **8 m** | Live test: two genuinely different signs shot from nearly the same standing position got near-identical GPS — **a real 3-panel sign was deleted** |
| 4 | **Content matching** ✅ | Only delete rules that are substantively the same regulation |

`Rule.describesSameRegulation()` compares rule class, day pattern, time windows,
and the type-specific field (duration for a time limit, zone for a permit). It
**ignores** rule id and free-text wording, which legitimately differ between two
independent reads of the same sign.

> **GPS accuracy and real-world sign spacing are the same order of magnitude, so
> no distance threshold can ever distinguish "same sign, re-read" from "different
> sign, nearby."** Compare content, not coordinates.
>
> And: never reuse a constant across a read path and a delete path just because
> the number looks right. A radius tuned for generosity becomes a weapon when
> tuned for destruction.

### 15.6 "NO DOUBLE PARKING" would have bricked a legal space

A `NO DOUBLE PARKING` panel on a real sign exposed a live correctness bug. With
only three rule types, it was mapped to `no_parking` — which the engine ranks
**highest restrictiveness, always NOT_PARKABLE**. So that panel would have
**permanently overridden** a perfectly legal 2-hour space on the same pole.

But double parking ≠ parking: it prohibits stopping in a *second row*, and says
nothing about the curb space.

Fix: a fourth type, `InformationalRule` — restrictiveness 0, always `PARKABLE`,
never outranks a real rule, but still correctly answers `PARKABLE` when it's the
only thing on the sign. **This is where the sealed interface paid for itself.**

Checking the gov mappers found the same gap — but *safely*: NYC's "NO PARK"
filter was silently dropping double-parking text. **Gov data had a coverage gap
(safe); camera scan had a correctness bug (unsafe).** Same missing feature,
opposite severity, entirely because of how each pipeline handles the unknown.

### 15.7 The opaque "Failed to fetch"

OpenRouter ran out of credits. The Java code threw an unhandled exception — and
**an unhandled exception in Lambda skips the proxy-integration response entirely,
including the CORS header.** The browser then refused to read the response and
reported a useless `"Failed to fetch"`, with no indication it was a billing problem.

Fix: `ScanHandler` catches `RuntimeException` and returns a real 503 through the
normal path, so the CORS header is always present; the real error goes to
CloudWatch.

> An error path that can't return an error is worse than no error path.

---

## 16. The temporal problem list

| # | Problem | Why it's hard | Handled by |
|---|---|---|---|
| 1 | **Midnight crossing** | `end < start` numerically; the calendar day isn't the rule's day | HEAD/TAIL split, day−1 lookback |
| 2 | **"When does this change?"** | Countdown needs a forward search, not just "now" | Day-by-day scan |
| 3 | **The 366-day cap** | Yearly patterns must be found; degenerate input must not loop forever | One leap year, then honest "no known boundary" |
| 4 | **Fake boundaries** | A ends 17:00, B starts 17:00 → nothing changes | Reject candidates where the answer is unchanged |
| 5 | **DST — spring** | 2:00–3:00 AM **doesn't exist** that day | `ZonedDateTime.of`; test pins it |
| 6 | **DST — fall** | 1:00–2:00 AM happens **twice** | Same; earlier offset; test pins it |
| 7 | **Nth weekday of month** | "1st and 3rd Tuesday"; some months have a 5th | `NthWeekdayOfMonth`, occurrences `{1,2,3,4,5,-1}` |
| 8 | **Floating holidays** | MLK is "3rd Monday in January", not a date | `UsFederalHolidayCalendar` |
| 9 | **Observed holidays** | July 4 on a Saturday → observed Friday | `observed()` shifts Sat→Fri, Sun→Mon |
| 10 | **New Year's edge case** | Jan 1 on a Saturday is observed **Dec 31 of the previous year** | Loop checks `year` **and** `year + 1` |
| 11 | **Time limit vs window end** | 2 h limit, park at 3 PM in an 8:30–5:30 window → 5 PM, not 5:30 | `min(boundary, now + limit)` |
| 12 | **Multiple rules disagreeing** | 4 panels on one pole | Most-restrictive-wins |
| 13 | **Left vs right side** | Arrows; different rules per side | Per-side verdicts; `DEPENDS` only if they differ |
| 14 | **Seasonal rules** | Effective / sunset dates | `DateRange`; far-future start returns its boundary directly |
| 15 | **Dead rules** | A rule past sunset must not pollute the countdown | Returns no boundary at all |

**#10 is the best one to lead with** — genuinely non-obvious, and a real four-line
detail in the code with a comment explaining exactly why the loop spans two years.

---

## 17. Costs, with real numbers

Pulled live from AWS Cost Explorer:

| Service | July 2026 | Aug 1–11 2026 |
|---|---|---|
| S3 (photos) | $0.0053 | |
| API Gateway | $0.0007 | |
| Secrets Manager | $0.00003 | |
| Lambda | **$0.00** | |
| CloudWatch | **$0.00** | |
| CloudFormation | **$0.00** | |
| Cost Explorer *(the tool that reported this)* | $0.01 | |
| **TOTAL** | **$0.0160** | **$0.0063** |

**Total AWS spend for the entire project: about 2.2 cents.**
Checking the bill ($0.01) cost more than running the app.

| Everything else | Cost |
|---|---|
| Supabase (Postgres + PostGIS, 500 MB) | **$0** free tier |
| GitHub + Actions (private repo) | **$0** |
| Vercel (web hosting + PWA) | **$0** hobby tier |
| Leaflet / OpenStreetMap / Apple Maps / Google Maps (Android) | **$0** |
| Java, Maven, JUnit, Jackson, PostGIS, proj4j | **$0** open source |
| Anthropic credit | bought $5, barely used |
| OpenRouter credits | a few dollars, mostly during the brief Sonnet period |

Per scan today: **~$0.0002** — about 5,000 scans per dollar.

---

## 18. Known limitations

Stated deliberately. These are real, and each has a known shape of fix.

1. **Phase 4 (evals) was never built.** `evals/` has a dataset schema and a stub.
   There is **no published accuracy number** and prompt changes are **not
   regression-gated**, despite that being an explicit success criterion. Three
   prompt changes shipped and the regressions were caught only by walking around
   scanning signs in person. The plan is known: LangSmith, 100–150 labelled
   photos, failure tagging by OCR / arrow / schema / reasoning. Real candidate
   photos already exist from live testing. **Top of the backlog.**
2. **Default timezone is `America/Los_Angeles`.** A New York sign checked without
   a `zone` parameter is evaluated in Pacific time, even though the GPS is right
   there in the request (§12).
3. **7 of 11 federal holidays.** Veterans Day, Columbus Day, Juneteenth and
   Presidents' Day are missing. Documented and additive (§4).
4. **`currentParserVersions()` is a hardcoded list.** A sixth city mapper needs a
   manual edit there. Fine at this scale; wants a registry if the city count grows.
   Caught and consciously accepted, not missed.
5. **No real auth.** `/reports` is gated by a shared secret with a **fail-closed**
   default (no secret configured = every request refused). A deliberate scope cut.
6. **Multi-panel signs can still split** into 2–3 cards when photographed
   separately. Investigated and **deliberately not patched** — the system cannot
   distinguish "3 photos of one sign's panels" from "3 distinct nearby signs"
   without a real merge-scans feature.
7. **Scan History thumbnails expire after an hour** (§10).
8. **Cold starts** — Java on Lambda, no SnapStart or provisioned concurrency.
   Mitigated because the common path is a DB read and the slow path already waits
   on the AI.
9. **Gov coverage gaps.** Seattle has ~10 more valid category codes unmapped.
   SF's "Paid + Permit" is deliberately unmapped, because paying is a valid
   alternative to holding a permit there and `PermitRule`'s `DEPENDS` would
   misstate it. Left unmapped rather than force-fit.
10. **Most-restrictive-wins is a single linear ranking.** "2 hour parking except
    permit holders unlimited" is genuinely a *combination*, not "whichever is more
    restrictive". A modelling simplification that holds for most real signs, not a theorem.

---

## 19. Glossary

| Term | Meaning |
|---|---|
| **API Gateway** | AWS service giving Lambda a public web address and routing URLs to functions |
| **ArcGIS** | Esri's mapping platform; one of the two open-data transports used |
| **base64** | Encoding binary (a photo) as plain text so it fits inside JSON |
| **CloudFormation / SAM** | Infrastructure as code — a file describing AWS resources, which AWS then builds |
| **CloudWatch** | AWS log collection |
| **Cold start** | The 1–3 s delay when Lambda boots a fresh container after being idle |
| **CORS** | Browser rule requiring an API to opt in to being called from another site |
| **Decorator** | A pattern: wrap an object to add behaviour without changing it |
| **ETL** | Extract, Transform, Load — import data from elsewhere, reshape it, store it |
| **Fixture** | Canned test data standing in for a real external system |
| **GEOGRAPHY** | PostGIS column type treating coordinates as points on the curved Earth |
| **GiST** | Postgres index type for shapes and locations |
| **Idempotent** | Running it twice has the same effect as running it once |
| **IAM** | AWS permissions system |
| **Instant** | An absolute moment in time, the same everywhere |
| **JSONB** | Postgres's binary JSON column type — flexible, still queryable |
| **Lambda** | AWS service running code only when a request arrives |
| **Leaflet** | JavaScript library that draws maps (has no map data of its own) |
| **Metro** | React Native's bundler; picks `.native.tsx` / `.web.tsx` by platform |
| **OpenRouter** | Middleman giving one API key access to many AI providers |
| **OpenStreetMap** | Volunteer-built world map dataset + free tile servers |
| **parser_version** | Tag on every stored rule recording which reader produced it; drives cache validity |
| **pgbouncer** | Connection pooler in front of Postgres; essential for serverless |
| **PostGIS** | Postgres extension that teaches it about the Earth |
| **Presigned URL** | Temporary signed link to one private S3 file |
| **proj4j** | Library converting between coordinate systems (NYC State Plane → GPS) |
| **PWA** | A website installable from a phone browser like an app |
| **S3** | AWS file storage |
| **SAM** | Serverless Application Model — shorthand for CloudFormation |
| **sealed interface** | Java: "exactly these types exist", making switches exhaustive at compile time |
| **Socrata** | Open-data platform many US cities publish on |
| **SRID** | Spatial Reference IDentifier — the number naming a coordinate system (4326 = GPS) |
| **SSM Parameter Store** | AWS encrypted secret storage |
| **Supabase** | Company hosting managed Postgres |
| **Tile** | A 256×256 px square of map image; maps are grids of these |
| **WGS84** | The global GPS coordinate standard; SRID 4326 |
| **ZoneId** | Timezone rulebook, e.g. `America/Los_Angeles`, which knows about DST |

---

## The five sentences

1. **The AI transcribes; Java decides.** The model turns a photo into structured
   data; deterministic, tested Java turns that data plus the current time into a
   verdict. Zero verdicts come from an AI.
2. **One schema, many sources.** Camera scans and five cities' open data
   normalise into the same shape, so the engine never knows the origin — which
   is why 5 cities were added with zero handler changes.
3. **PostGIS makes "near me" a real database operation.** A `GEOGRAPHY` column
   plus a GiST index turns "within 25 metres" from a 250,000-row scan into an
   instant lookup with correct curved-Earth distances in actual metres.
4. **Serverless made it free.** Five Lambdas behind an API Gateway cost about
   2 cents in total, because you pay per request instead of renting a computer.
5. **Real streets found bugs no test could.** An ETL wired to the wrong dataset
   that imported nothing while all tests passed; a parser that read "except
   Sunday" as "only Sunday" and inverted 11,820 rules; and a dedup problem that
   took three wrong answers before the real insight — that GPS accuracy and sign
   spacing are too close for any radius to separate "same sign" from "different
   sign", so you must compare content, not coordinates.
