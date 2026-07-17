# Phase 1 Study Guide — Simple Explanations + Interview Answers

> How to use this file: each topic has three parts.
> **In simple words** — read this to understand.
> **In our project** — where it actually lives in Parkable (open the file, see it).
> **Say this in the interview** — a short, confident answer you can speak out loud.

---

## 1. Java Time API (`java.time`)

**In simple words:**
- An **`Instant`** is one exact moment on the global timeline — like a timestamp on the whole Earth. `2026-07-14T18:04:00Z` is the same moment for everyone, everywhere. The `Z` means UTC.
- A **`LocalDateTime`** is a wall-clock reading with NO location: "July 14, 6:04 PM". Six words that mean a *different moment* in Tokyo vs San Francisco. Dangerous on its own.
- A **`ZonedDateTime`** = LocalDateTime + a **`ZoneId`** (like `America/Los_Angeles`). Now it's unambiguous again.
- Converting: `instant.atZone(zone)` gives you the local wall-clock view of a global moment. That's the key move.
- **DST (Daylight Saving Time)**: twice a year the wall clock jumps. In spring, 2:00–3:00 AM *does not exist* (clock jumps 2→3). In fall, 1:00–2:00 AM *happens twice*. Any code comparing times must survive both.

**In our project:** parking signs speak wall-clock language ("8 AM – 6 PM"), but verdicts must be exact moments. `RulesEngine.evaluate()` receives an `Instant` + `ZoneId`, converts once with `instant.atZone(zone)`, and all window math happens in local time. We have tests pinned exactly on the DST jump nights so a JDK update can never silently change our answers.

**Say this in the interview:**
"I learned to keep a hard boundary between machine time and human time. Internally everything is an `Instant` in UTC; I convert to `ZonedDateTime` only at the edges, because parking signs are written in local wall-clock time. And I wrote explicit unit tests on the DST transition nights — the 2 AM that doesn't exist in spring and the 1 AM that happens twice in fall — so those edge cases are locked down by tests, not by hope."

---

## 2. Records

**In simple words:** a `record` is a Java class for *carrying data* that writes itself. You declare `record TimeWindow(LocalTime start, LocalTime end) {}` and Java generates the constructor, getters, `equals`, `hashCode`, and `toString`. Records are **immutable** — once created, values never change. Immutable objects can't be corrupted by other code, are safe to share between threads, and behave predictably in tests.

**In our project:** almost every data type is a record — `TimeWindow`, `RuleMetadata`, `VerdictResult`, all the JSON DTOs. A "compact constructor" adds validation, e.g. `DateRange` throws immediately if the end date is before the start date — bad data cannot even be constructed.

**Say this in the interview:**
"My whole domain model is immutable records. That killed a whole category of bugs: nothing can mutate a rule after it's created, two rules with the same values are automatically equal, and I use compact constructors to make invalid objects impossible to construct — fail fast at creation, not deep inside the engine."

---

## 3. Sealed Interfaces

**In simple words:** `sealed interface Rule permits NoParkingRule, TimeLimitRule, PermitRule` tells the compiler: "these three are the ONLY implementations that will ever exist." The payoff: when you `switch` over a sealed type, the **compiler forces you to handle every case** — no `default` needed. If someone adds a fourth rule type next year, every switch in the codebase becomes a compile error until it's handled. Forgetting becomes impossible.

**In our project:** `Rule` (three rule types) and `ExtractionResult` (`Success` / `NeedsReview`). The engine's verdict logic is a switch over `Rule` — add a rule type and the compiler points at every place that must decide what it means.

**Say this in the interview:**
"I used sealed interfaces so the compiler enforces exhaustiveness. In a rules engine that's huge: adding a new rule type can't silently produce wrong verdicts, because the code won't compile until every decision point explicitly handles it. It turns a runtime bug into a compile-time error."

---

## 4. Design Patterns (know all six — each one is a story)

**Strategy** — *swappable algorithms behind one interface.*
Simple: define an interface, provide multiple implementations, callers don't care which one they get.
Ours: `VisionExtractor` has `ClaudeVisionExtractor` (real AI, costs money) and `FixtureVisionExtractor` (reads a JSON file, free, offline). Tests and CLI pick with one flag.
Say: "Because extraction is behind a Strategy interface, my 135 tests run offline for free, and switching AI providers is a new class — zero engine changes."

**Decorator** — *wrap an object to add behavior without touching it.*
Ours: `ValidatingRetryingVisionExtractor` wraps ANY extractor and adds: validate the output → retry once if bad → give up honestly. The wrapped class doesn't know it's wrapped.
Say: "Validation and retry live in a Decorator, so they're written once and work identically over the real extractor and the test stub — that's Open/Closed: I extended behavior without modifying the extractors."

**Factory** — *one central place that builds objects from raw input.*
Ours: `RuleFactory` turns validated JSON into typed `Rule` objects (maps `"street_cleaning"` → `NoParkingRule`, red curb → `NoParkingRule`, blue curb → `PermitRule`...). All conversion decisions in one reviewable file.
Say: "All JSON-to-domain mapping funnels through one Factory, so mapping policy — like 'a red curb means no parking' — is documented and tested in exactly one place."

**Builder** — *construct complex objects step by step with readable code.*
Ours: `RuleBuilder`, used only in tests: `new RuleBuilder().noParking().onDays(MONDAY).duringWindow(...).build()`. Tests read like sentences.
Say: "I kept the Builder test-only on purpose — production data must come through the validated Factory path; the Builder's convenient defaults would hide extraction gaps."

**Adapter** — *translate an external system's shape into your own interface.*
Ours: `ClaudeVisionExtractor` adapts the Anthropic SDK (its request/response types) to our small `VisionExtractor` interface. The rest of the codebase never imports anything from the SDK.
Say: "The vendor SDK is quarantined inside one Adapter class. If Anthropic changes their API — which happened during development — exactly one file changes."

**Repository** — *hide the storage technology behind an interface.*
Ours: `RuleRepository` interface with `InMemoryRuleRepository` for Phase 1; Phase 2 swaps in Postgres without touching any caller.
Say: "Storage is behind a Repository seam, so going from in-memory to PostGIS is a new implementation class, not a refactor."

**Bonus principle — composition over inheritance:** every rule *has-a* `RuleMetadata` record instead of extending an abstract base class. Shared data without fragile inheritance chains.

---

## 5. JSON Schema

**In simple words:** a JSON Schema is a contract for JSON — a JSON document that says "a valid document has these required fields, these types, these allowed enum values, and if field X = 'time_limit' then field Y is also required." A validator library checks any document against it and lists every violation.

**In our project:** [parking-rule-schema.json](backend/src/main/resources/schema/parking-rule-schema.json) is the contract between the LLM and the rules engine. The AI is told "return JSON matching this schema"; the validator then *enforces* it. Conditional rules (`if type == time_limit then restriction.duration_minutes required`) catch half-complete extractions. What schemas *can't* express (e.g., "start time must not equal end time", "this flag contradicts these values") lives in a second, hand-written `SemanticRuleValidator`.

**Say this in the interview:**
"I treat the JSON Schema as the contract between the AI and the deterministic core — the AI's output is guilty until proven valid. And I learned schemas have limits: cross-field logic can't be expressed in them, so I layered a semantic validator behind the structural one. Both feed a retry-once-then-fail-honestly pipeline."

---

## 6. Jackson (JSON ↔ Java)

**In simple words:** Jackson is the standard Java library that converts JSON text into Java objects and back. `readTree()` gives you a raw tree (`JsonNode`) you can inspect; `treeToValue()` maps the tree onto your classes. `@JsonProperty("snake_case_name")` connects JSON field names to Java field names.

**In our project:** DTO records like `ExtractionEnvelope` mirror the schema, with `@JsonProperty` for names like `parser_version` and `@JsonIgnoreProperties(ignoreUnknown = true)`. Two deliberate choices: (1) DTOs are **lenient** — every field nullable — because strictness is the validators' job, and a strict DTO would turn a retryable bad extraction into a crash; (2) we validate the raw `JsonNode` **before** mapping, so a type mismatch becomes a validation error, not an exception.

**Say this in the interview:**
"The interesting decision wasn't using Jackson, it was the ordering: schema-validate the raw JSON tree first, map to typed objects second. If you map first, a wrong type crashes with a mapping exception; validating first turns the same input into a clean validation failure that flows into my retry logic. Parsing and validating are separate responsibilities."

---

## 7. Testing: JUnit 5, AssertJ, Mockito, TDD

**In simple words:**
- **JUnit 5** runs the tests (`@Test`, nested test classes).
- **AssertJ** gives readable assertions: `assertThat(result.verdict()).isEqualTo(NOT_PARKABLE)` — reads like English, and failure messages are excellent.
- **Mockito** creates fake objects: `mock(VisionExtractor.class)` + "when called, return X the first time and Y the second time" — perfect for testing retry logic without a real AI.
- **TDD (test-driven development)**: for tricky logic, write the failing test FIRST, then the code. The test is your specification.

**In our project:** 135 tests. The temporal evaluator was built test-first — DST, midnight-crossing, nth-weekday tests existed before the implementation. The retry decorator's tests use a Mockito mock that fails once then succeeds, proving exactly one retry happens. A "golden" end-to-end test drives photo → JSON → validation → factory → engine and asserts the exact verdict and expiry instant.

**Say this in the interview:**
"For temporal logic I worked strictly test-first, because that's where the subtle bugs are — I wrote the 'Monday 11 PM–2 AM sign checked on Tuesday 1 AM' test before writing the evaluator, and it drove the design. And I used Mockito to prove behavioral contracts, like 'the decorator calls the extractor exactly twice when the first result is invalid' — not just that the output looks right, but that the *interaction* is right."

---

## 8. Maven

**In simple words:** Maven is Java's build tool. `pom.xml` declares your dependencies (it downloads them) and plugins. Key commands: `mvn compile` (build), `mvn test` (build + run tests — the build FAILS if any test fails), `mvn package` (build the jar). The **Shade plugin** builds a "fat jar" — your code plus every dependency in one file, so `java -jar parkable-cli.jar` runs on any machine with just a JRE.

**In our project:** [backend/pom.xml](backend/pom.xml) — dependencies (Jackson, networknt schema validator, Anthropic SDK, JUnit/AssertJ/Mockito), Surefire running tests on every build, Shade producing `parkable-cli.jar` with `ScanCLI` as the entry point. Real story in there: the JDK 25 upgrade required attaching Mockito as a `-javaagent` and a Byte Buddy compatibility flag — both documented in the pom with comments saying when to remove them.

**Say this in the interview:**
"Beyond the basics, I debugged a real toolchain issue: after a JDK upgrade, Mockito failed because newer JDKs block dynamic agent attach, and its bytecode library didn't officially support the new class-file version yet. I fixed it properly — explicit `-javaagent` in Surefire plus a documented compatibility flag — instead of downgrading and hiding the problem."

---

## 9. The Temporal Edge Cases (our project's signature — know these cold)

**Midnight-crossing window (11 PM – 2 AM):**
Problem: the end time (02:00) is *before* the start (23:00), so naive `start <= t < end` matches nothing.
Fix: if the window wraps, the check flips from AND to OR — `t >= 23:00 OR t < 02:00`. Works because no single time satisfies both sides of a wrapped window.

**Yesterday-tail spillover:**
Problem: a "Monday 11 PM – 2 AM" rule must be active at *Tuesday 1 AM* — but Tuesday isn't Monday, so a naive day check says "not active." Wrong verdict, towed car.
Fix: also check whether *yesterday* matched the day pattern AND had a midnight-crossing window that still contains the current time.

**Nth weekday of month ("1st & 3rd Tuesday" street cleaning):**
occurrence = `(dayOfMonth - 1) / 7 + 1`. "Last" (sentinel `-1`): a date is the last occurrence exactly when the same weekday 7 days later lands in the next month — and a 4th Friday can *also* be the last Friday, so we check both.

**Observed holidays:**
July 4 on a Saturday is *observed* on Friday July 3 — an "except holidays" sign is suspended on the observed day. Floating holidays (Thanksgiving = 4th Thursday of November) are computed, not hard-coded.

**Say this in the interview (the summary line):**
"The rules engine looks simple until you hit the edges: windows that cross midnight, Monday-night rules still active on Tuesday at 1 AM, 'last Friday' in months where the 4th Friday IS the last, holidays observed on a different day than they fall, and the DST nights where an hour vanishes or repeats. I wrote failing tests for every one of those before implementing, and that test suite is why I trust the engine enough to put a verdict in front of a user."

---

*Companion file: [QUESTIONS.md](QUESTIONS.md) has 30 project-specific interview Q&As. This file teaches the concepts; that file rehearses the project.*
