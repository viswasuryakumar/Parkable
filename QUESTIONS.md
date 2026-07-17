# Parkable — Interview Questions & Answers

> Study file for interview prep. Every Q&A is grounded in what this project actually
> does — you can open the referenced file and show the code while answering.
> Maintained by Claude Code; ask it to "update QUESTIONS.md" after new work lands
> and it will resume from the checkpoint at the bottom.

---

## A. The Project & Its Architecture

**Q1. What is Parkable? Give the elevator pitch.**
A mobile app that answers "Can I park here right now?" You point your camera at a parking sign; an AI model reads the sign into structured JSON, and a deterministic Java rules engine evaluates those rules against the current time to produce PARKABLE / NOT_PARKABLE / DEPENDS — with the exact rule that triggered the verdict, a human-readable trace, and a "valid until" time. Answers also come instantly from government open data or a geospatial cache when available.

**Q2. What is the single most important design decision in the project?**
**Perception ≠ Decision.** The LLM only *transcribes* the sign into JSON (perception); it never decides whether you can park. The verdict comes from a plain-Java rules engine (decision) that is 100% unit-testable and deterministic. This means zero LLM-decided verdicts: every answer is traceable to a specific rule, evaluated by tested code, from a tagged source.

**Q3. Why not just ask the LLM "can I park here?" directly — it would be much less code?**
Three reasons. (1) *Testability*: you can't write a unit test proving an LLM will answer correctly; you can prove a rules engine handles midnight-crossing windows with a test. (2) *Honesty*: an LLM answers confidently even when wrong; our engine either has valid rules or says "retake the photo." (3) *Cost & traceability*: parsed rules are cached and reusable; every verdict can be audited step-by-step in the trace.

**Q4. Walk me through one scan end-to-end.**
Photo bytes → `VisionExtractor.extract()` (Claude reads the sign, returns a JSON "extraction envelope") → JSON Schema validation (structure) → semantic validation (cross-field logic) → if invalid, one retry, then an honest "retake photo" → `RuleFactory` maps validated JSON to typed domain `Rule` objects → `RulesEngine.evaluate(rules, instant, zone, side)` runs each rule through the `TemporalRuleEvaluator`, resolves conflicts, applies most-restrictive-wins → `VerdictResult` with verdict + triggering rule + validUntil + trace. The CLI (`ScanCLI`) just orchestrates; it contains zero business logic.

**Q5. What are the three verdicts and why is there a third one?**
PARKABLE, NOT_PARKABLE, and DEPENDS. DEPENDS is the honest answer when the system *cannot know*: a permit rule is active (we can't verify you hold a permit) or two active rules on opposite sides of the street disagree and we don't know which side the car is on. Refusing to guess is a feature — a confidently wrong YES gets a user towed.

**Q6. What data sources feed the system?**
Two, normalized into one schema: government open datasets (e.g., SFMTA — instant, free answers) and camera scans via the LLM (cached geospatially for reuse). Both carry `source` and `parser_version` tags so every stored rule is traceable and reprocessable.

---

## B. The Rules Engine & Temporal Logic (the hard part)

**Q7. How do you represent "No Parking 11 PM – 2 AM"? What's tricky about it?**
The window crosses midnight, so its end (02:00) is numerically *before* its start (23:00). `TimeWindow.contains(t)` handles it with a boolean flip: normal windows use `t >= start AND t < end`; midnight-crossing windows use `t >= start OR t < end` — the OR works because no single time can satisfy both sides of a wrapped window. (See `TimeWindow.java`.)

**Q8. What is the "yesterday-tail" bug that naive implementations have?**
A "Monday 11 PM – 2 AM" rule must still be active at *Tuesday 1 AM* — Monday's window spills into Tuesday. A naive check ("does today's pattern match?") says Tuesday ≠ Monday and wrongly answers PARKABLE. Our evaluator explicitly also checks whether *yesterday* matched the day pattern and whether yesterday's midnight-crossing window still contains the current time.

**Q9. How does the engine handle Daylight Saving Time?**
All boundary construction goes through `ZonedDateTime.of(LocalDateTime, ZoneId)`, which applies the JDK's standard resolution for the spring-forward gap (2–3 AM doesn't exist) and the fall-back overlap (1–2 AM happens twice). We pinned this behavior with dedicated tests at both edges, so if JDK/timezone-data behavior ever changes, a test fails instead of a user getting a wrong verdict.

**Q10. How do you model "street cleaning 1st and 3rd Tuesday of the month"?**
`NthWeekdayOfMonth(weekday, occurrences)`: occurrence = `(dayOfMonth - 1) / 7 + 1`. The sentinel `-1` means "last": a date is the *last* occurrence exactly when the same weekday seven days later falls in the next month — checked *in addition to* the numeric occurrence, since a 4th Friday can simultaneously be the last Friday.

**Q11. How do "except holidays" signs work?**
Each rule carries a `HolidayPolicy`; the evaluator consults a `HolidayCalendar` interface (implementation: `UsFederalHolidayCalendar` — fixed dates like July 4, floating ones like Thanksgiving = 4th Thursday of November, plus observed shifting: Saturday holidays observed Friday, Sunday ones observed Monday). Because it's an interface, tests can inject `date -> false` and a second city's calendar later needs zero engine changes.

**Q12. Why does the engine take the evaluation time as a parameter instead of calling `Instant.now()`?**
Reproducibility and testability. Any verdict can be recomputed for any moment ("what was the verdict last Tuesday 9 AM?"), tests can pin exact instants (DST edges, holidays), and cached/stored results are meaningful. `Instant.now()` (via `Clock`) exists only at the outermost edges — the CLI entry point and extraction timestamps — never inside business logic. We can grep the codebase to enforce this.

**Q13. What happens when multiple rules are active at once?**
Most-restrictive-wins ranking: NO_PARKING (3) > PERMIT (2) > TIME_LIMIT (1). Ties keep list order for deterministic output. Side-of-street conflicts only produce DEPENDS when they *matter* — if LEFT and RIGHT rules would both yield NOT_PARKABLE, we don't manufacture false ambiguity. `validUntil` is the soonest boundary across ALL rules, including inactive ones about to activate — "when could this answer change?"

**Q14. How do you avoid an infinite loop scanning for the next time a rule applies?**
The forward scan for the next matching day is bounded (MAX_LOOKAHEAD = 366 days). A degenerate rule whose day pattern never matches (e.g., an empty day set) returns "inactive, no boundary" instead of hanging.

---

## C. Java Language & Design

**Q15. Which modern Java features does the project lean on, and why?**
**Records** for immutable value types with free `equals`/`hashCode` (`TimeWindow`, `VerdictResult`, all DTOs); **sealed interfaces** so the compiler enforces exhaustive handling (`Rule permits NoParkingRule, TimeLimitRule, PermitRule` — the engine's `switch` over rule types cannot silently miss a case; same for `ExtractionResult permits Success, NeedsReview`); **pattern matching in switch**; `java.time` throughout; text blocks for the LLM prompt.

**Q16. Name the design patterns in the codebase and where each lives.**
- **Strategy** — `VisionExtractor` interface; `ClaudeVisionExtractor` and `FixtureVisionExtractor` are swappable providers.
- **Decorator** — `ValidatingRetryingVisionExtractor` wraps *any* extractor and adds validate→retry→give-up behavior without modifying it (Open/Closed principle).
- **Factory** — `RuleFactory` centralizes JSON-DTO → domain-`Rule` mapping.
- **Builder** — `RuleBuilder`, a fluent test-only constructor for rules.
- **Adapter** — `ClaudeVisionExtractor` adapts the Anthropic SDK to our domain interface; `CameraScanSignSource` adapts an extractor to the `SignSource` interface.
- **Repository** — `RuleRepository` + `InMemoryRuleRepository`, the storage seam Phase 2 swaps for Postgres.

**Q17. Why composition over inheritance in the domain model?**
Every concrete rule *has-a* `RuleMetadata` (id, description, day pattern, time windows, holiday policy, direction) instead of extending an abstract base class. Shared data lives in one record; rule types stay flat and sealed; no fragile-base-class problems; adding a rule type never risks breaking siblings.

**Q18. Why is `ExtractionResult` a sealed result type instead of throwing exceptions?**
"Couldn't read the sign" is an *expected outcome*, not an exceptional one — it happens on every blurry photo. `NeedsReview` makes callers handle it explicitly (the compiler forces the branch). Exceptions (`VisionExtractionException`) are reserved for genuinely exceptional failures: network errors, missing files. Rule of thumb: expected outcomes are return values; surprises are exceptions.

---

## D. LLM Integration & Validation

**Q19. How exactly is the LLM called?**
`ClaudeVisionExtractor` uses the official Anthropic Java SDK: it base64-encodes the photo into an image content block, attaches a prompt containing our full JSON Schema plus rules ("return ONLY JSON", "use LOW confidence if blurry", "never decide whether parking is allowed"), and calls the Messages API (`claude-opus-4-8`). The response text is located, parsed, and mapped to DTOs. Non-JSON output becomes `NeedsReview` (retryable), not a crash.

**Q20. LLMs return garbage sometimes. How do you defend against that?**
Layered validation with one retry:
1. **Structural** — `RuleJsonSchemaValidator` runs the raw JSON against a JSON Schema (draft 2020-12, networknt library): required fields, enums, types, conditionals like "time_limit requires duration_minutes".
2. **Semantic** — `SemanticRuleValidator` catches what schemas can't express: duplicate rule IDs, `start == end` windows (ambiguous — never guess), a `crosses_midnight` flag contradicting its own times, sunset dates before effective dates.
3. **Retry once**, then return `NeedsReview` with a user-honest message ("retake with the full sign visible"); the technical errors go to a debug trace, never raw to the user. The engine is *never invoked* on unvalidated data.

**Q21. Why validate the raw JSON before mapping it to Java objects?**
Order matters: if Jackson mapped first, a type mismatch (`"confidence": "very high"` instead of a number) would throw a mapping exception — crashing instead of retrying. Validating the raw `JsonNode` first turns bad shape into a *validation failure* that flows into the retry path. That's also why `Success` carries both the raw JSON (for the schema validator) and the typed DTO (for the semantic validator). The DTOs themselves are deliberately lenient — all fields nullable — because strictness belongs to the validators.

**Q22. What does a scan cost and how do you keep costs down?**
Roughly 3–6 cents per scan on `claude-opus-4-8` (image tokens + schema prompt + JSON response). Mitigations: a PostGIS geospatial cache (each sign paid for once; anyone within 25 m reuses it), free government-data answers where coverage exists, an offline fixture extractor so tests/CI cost $0, and a one-constant switch to a ~10× cheaper model (`claude-haiku-4-5`) if Phase 4 evals show its accuracy suffices.

**Q23. What is the reproducibility contract?**
Every extraction stores the photo reference + `parser_version` + timestamp (and GPS in `ExtractionRecord`). When the extractor improves (better prompt, new model), old photos can be re-processed and results compared; a cache hit is only valid if it matches the current `parser_version`. You can always answer "which parser produced this rule, from which photo, when?"

---

## E. Testing & Tooling

**Q24. How is the system tested without paying for API calls?**
Everything downstream of the `VisionExtractor` interface is tested through `FixtureVisionExtractor`, which returns pre-recorded JSON from a file next to the image — offline, free, deterministic. A golden end-to-end test drives fixture image → extraction → validation → factory → engine and asserts the exact verdict and validUntil instant. The one live-API test is opt-in via an env var and skipped everywhere else. 135 tests total; the temporal evaluator has the densest coverage because that's where the hard bugs live (DST, midnight, nth-weekday, holidays).

**Q25. Why does the CLI use distinct exit codes?**
0=PARKABLE, 1=NOT_PARKABLE, 2=NEEDS_REVIEW, 3=DEPENDS, 64=usage error, 66=unreadable image. Scripts and tests can branch on the outcome without parsing text — the CLI is itself a testable, scriptable component. `ScanCLI.main()` is a one-liner; the real logic is in a `run(args, clock, out, err)` method tests can call in-process with a fixed clock and captured streams.

**Q26. Tell me about a real build/tooling problem you debugged.**
Upgrading to JDK 25 broke Mockito twice. First, JDK 25 disallows the dynamic agent self-attach Mockito's inline mock-maker uses — fixed by attaching `mockito-core` as an explicit `-javaagent` in Surefire (path resolved via the maven-dependency-plugin's `properties` goal). Second, Byte Buddy didn't officially support Java 25 class files (version 69) yet — bridged with `-Dnet.bytebuddy.experimental=true`, documented to remove once support lands. Lesson: a "simple" JDK upgrade cascades through the toolchain, and pinning fixes with comments about *when to remove them* matters.

**Q27. What's in the deliverable jar and how is it built?**
`mvn package` uses the Maven Shade plugin to build a single self-contained `parkable-cli.jar` (~30 MB — includes Jackson, the schema validator, and the Anthropic SDK) with `ScanCLI` as `Main-Class`. `java -jar parkable-cli.jar photo.jpg --now=... --zone=... --extractor=stub|claude` runs the whole pipeline anywhere with a JRE.

---

## F. Data & Phase 2 Groundwork

**Q28. Why PostGIS `GEOGRAPHY` instead of plain lat/lng columns?**
`GEOGRAPHY(Point, 4326)` makes distance queries work in real metres on the earth's surface — `ST_DWithin(location, point, 25)` finds rules within 25 m without application-side haversine math. A GiST spatial index makes that lookup fast. Gotcha worth mentioning: PostGIS points are `(longitude, latitude)` — reversed from the colloquial order.

**Q29. Why store rules as JSONB in Postgres?**
The rule schema is deep and evolving (nested time windows, day patterns, exceptions). JSONB stores the full document queryably without a brittle 15-table normalization, while the columns that *matter* for lookup (location, source, parser_version, created_at) are proper indexed columns. Best of both.

**Q30. How was this project actually built? (process question)**
Three AI coding agents working in parallel — Claude Code (manager), Codex, and GitHub Copilot — coordinated through two files: `AGENTS.md` (rules: strict directory ownership so no agent ever edits another's files, definition of done, architecture rules) and `PROGRESS.md` (task board with statuses + append-only log). Tasks were partitioned so nobody blocks anybody; interfaces were coded to a written spec rather than to another agent's work-in-progress. The manager reviews every deliverable before it's committed to git — that review caught real bugs (a missing app entry point, a schema field the docs promised but the schema lacked). Also a real war story: a fourth tool (a Copilot "modernize" run) upgraded the project to Java 25 *mid-session*, which is exactly why the review gate and version control exist.

---

<!-- ================================================================
CHECKPOINT — for Claude Code, do not delete or edit by hand.
Covered so far: project start → Phase 1 complete & closed out
  (Stages A–C, multi-agent setup, C1–C7 / X1–X3 / P1–P2,
   git history b403bb7..cba2693, 135 tests, parkable-cli.jar)
Last question: Q30
Next update: resume from Phase 2 (AWS backend: Lambda, API Gateway,
  S3, SSM, Supabase/PostGIS integration) and anything after.
================================================================ -->
