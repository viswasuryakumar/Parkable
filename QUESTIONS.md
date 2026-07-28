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

## G. Phase 2 — AWS Backend

**Q31. How does the Lambda architecture work end to end?**
API Gateway routes (`POST /scan`, `GET /check`, `GET /nearby`, `POST /report`, `GET /reports`) each map to their own Lambda function (`ScanHandler`, `CheckHandler`, `NearbyHandler`, `ReportHandler`, `ReportsHandler`). Every handler is a thin `RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent>` that parses params, calls a port interface (`RuleLookup`/`RuleRepository`), delegates to the SAME `RulesEngine` the CLI uses, and formats JSON. Zero business logic in the handler — architecture rule #1, checked at every review.

**Q32. How is config/secrets management done?**
`EnvConfig` only reads plain environment variables — it never calls AWS itself. The SAM template resolves SSM Parameter Store values (`/parkable/db-url`, `/parkable/openrouter-api-key`, `/parkable/admin-secret`, …) into those env vars at deploy time via `AWS::SSM::Parameter::Value<String>` parameter types. No secret ever touches git; local dev uses a gitignored `.env`.

**Q33. Why does `StorageStack`/`RuleLookup` exist as a seam?**
`StorageStack.from(EnvConfig)` picks a real Postgres repository when `PARKABLE_DB_URL` is set, otherwise an in-memory fallback for tests/local dev. Classic Repository pattern — every handler depends on the `RuleLookup`/`RuleRepository` interface only, never a concrete database class, so Postgres could be swapped for anything else without touching a single handler.

**Q34. Real deploy bugs that only surfaced at actual deploy time — walk through a few.**
No local Lambda emulator was available (no Docker/admin rights), so the real deploy WAS the smoke test, and every round found genuine bugs no unit test could catch: (1) the SAM template set `AWS_REGION` as a Lambda env var — a reserved key the Lambda API rejects outright (also redundant; Lambda supplies it automatically). (2) The SSM `db-url` was missing its `jdbc:` prefix (Supabase's dashboard shows a bare `postgresql://…`), and the resulting driver exception leaked the DB password into a CloudWatch log *and* the chat session — fixed at the code level (redact before any exception message can include it), deleted the leaked log entry, and made an informed call with the user not to rotate the password since it's a personal, no-other-clients DB. (3) Even with the prefix fixed, the pg JDBC driver doesn't parse the libpq-style `user:pass@host` credentials Supabase hands out — it was trying to resolve the *entire string* as one hostname. Fixed by parsing credentials out and using the 3-arg `DriverManager.getConnection(url, user, password)` overload. (4) A bad/unreadable test image made OpenRouter return HTTP 400, which the extractor treated as a hard failure (502) instead of the honest `NeedsReview` "retake the photo" path the whole architecture is built around — an edge case only a real bad scan could expose.

**Q35. Why are `/check` and `/nearby` different response shapes instead of one unified type?**
Because they answer different questions: `/check` computes a *verdict* (a decision, at a point); `/nearby` lists *raw rule summaries* without deciding anything (browsing). Forcing them into one shape would blur that distinction. `/nearby` returns `{"rules":[{rule_id, description, source, parser_version, scan_id, days, hours, lat, lng, distance_m, photo_url}]}`; `/check` returns a single verdict object, or (after a later fix, Q54) `{"signs":[...]}` when several distinct signs are genuinely nearby.

**Q36. Tell me about the CORS bug.**
The first real browser test (Expo web build) failed with a plain "Failed to fetch" — CORS had never been configured, because *native* app clients don't enforce CORS at all, so every earlier curl/native smoke test was structurally incapable of catching it. Only a real browser client could surface it. Fixed with `Access-Control-Allow-Origin: *` on every Lambda response plus the SAM template's `Cors:` block for API Gateway's OPTIONS preflight.

---

## H. Phase 2.5 — Government Data ETL (multi-city)

**Q37. What's the two-adapter transport design, and why two?**
A `GovDataFeed` interface (bulk enumeration) with two generic implementations: `SocrataGovDataFeed` (Socrata's SODA API, `$limit`/`$offset` pagination — used by NYC/Chicago/LA) and `ArcGisGovDataFeed` (ArcGIS FeatureServer, `resultOffset` pagination — used by SF/Seattle). Deliberately distinct from Phase 1's `SignSource` (a *point-query* interface for camera scans, one sign at a time) — conflating a bulk-import interface with a point-query one was an early design mistake caught and written up before it shipped.

**Q38. How does a per-city `GovRuleMapper` work, and what was the discipline around it?**
One mapper per city (NYC, Chicago, LA, SF, Seattle), each translating that city's own raw field names into the shared domain `Rule` + WGS84 lat/lng (a `MappedRule`). The hard rule: verify real field names via a live `$limit=3` query BEFORE writing any mapping code — never guess from documentation. That discipline is exactly what caught the much bigger bug in Q41.

**Q39. NYC needed a coordinate system conversion — what was the problem and the fix?**
NYC's raw dataset only carries NY State Plane (EPSG:2263) x/y, not lat/lng. Fixed with `proj4j` — plus `proj4j-epsg` as a *separate* Maven artifact (plain `proj4j` throws "Unable to access CRS file" without it) — and numerically verified against a real record: `(982004, 204840) → (40.7289, -74.0081)`, which lands precisely on West Houston St, matching that same record's own street-name field.

**Q40. The "no double parking" gap — why was this a correctness bug, not just missing coverage?**
The domain model originally had only `NoParkingRule`/`TimeLimitRule`/`PermitRule`. A scanned "No Double Parking Any Time" sign had nowhere honest to map to, and the engine ranks `NoParkingRule` as always-NOT_PARKABLE and highest-restrictiveness — so it would have silently and *permanently* overridden a legitimate time-limited rule on the same pole (double parking prohibits a second row, it doesn't prohibit parking at the curb). Fixed additively with a 4th sealed type, `InformationalRule` (restrictiveness 0, always resolves PARKABLE, never wins the most-restrictive ranking) — no engine rewrite needed, which is exactly what the sealed-interface design is for. Gov mappers, by contrast, had the *safer* failure mode: NYC's mapper only matched text containing "NO PARK," so it silently dropped (never misclassified) "No Standing"/"No Stopping" text.

**Q41. The NYC wrong-dataset bug — walk through it; it's a good process story.**
The user asked "did you actually check the DB for categories we might be missing?" — and the DB itself can't answer that, since gov mappers filter *before* storage, so a query only shows what already got accepted, never what got silently dropped. Went to each city's live raw feed instead, and found something bigger than a missing category: NYC's ETL had been pointed at the *wrong* Socrata resource the whole time (`afgb-4qw7`, 118 rows, all "PRESS NYP LICENSE PLATES ONLY" signs) instead of `nfid-uabd`, the real 440,656-row "Parking Regulation Locations and Signs" dataset the project's own docs referenced. Confirmed live: `gov-nyc-mapper-v1` had contributed exactly **zero** production rows despite every test passing — the fixture-based tests never exercised a real resource id, so nothing could have caught it. Fixing the id plus extending day-pattern parsing (individual day names, "ANY TIME", EXCEPT-day handling) took NYC from 0 → 195,133 rows. Then, re-checking the live `/nearby` output, caught a *self-inflicted* bug in that same fix: "NO PARKING 6AM-8AM EXCEPT SUNDAY" parsed as `days: Sun` — the new day-name scanner had picked up "SUNDAY" from the EXCEPT clause and inverted the sign's actual meaning (every day *except* Sunday). ~11,820 live rows contained both "NO PARK" and "EXCEPT," so not a rare edge case. Fixed by treating day names after EXCEPT as exclusions from a base set, then re-ran the (idempotent, upsert-by-stable-id) import, which corrected the bad rows in place.

**Q42. Describe a production incident that only appeared at real scale.**
The importer worked fine at `--limit=5` but died mid-import at full scale with "prepared statement S_1 already exists." Root cause: Supabase's pooled port (6543) is pgbouncer running in *transaction mode*, which breaks the pg JDBC driver's server-side prepared-statement cache once a statement executes enough times on a connection pgbouncer is silently rotating underneath. Fixed with `prepareThreshold=0` (disables that caching) plus a genuine efficiency fix alongside it: a batch `saveAll` API so the Postgres repository reuses one connection with 500-row batch flushes instead of opening on the order of 55,000 individual connections for a 55K-row import.

---

## I. Phase 3 — Mobile App & Real-World Debugging

**Q43. What's the mobile stack, and why switch to React Navigation?**
Expo (managed workflow), React Native, TypeScript. It started as a hand-rolled 3-screen conditional-render switcher; once the feature set grew, that couldn't express a real back stack or a modal screen (Report Sign), so it was replaced with React Navigation (bottom-tabs + native-stack).

**Q44. How does mobile avoid re-implementing rules-engine logic on the client?**
It doesn't, ever — every verdict decision stays server-side; the client only captures/displays (GPS, camera, rendering the trace/verdict/countdown). The one place this was almost violated: a "Remind me" action on Nearby rule panels deliberately only parses the simple day-list string the backend already sends (e.g. "Mon, Wed, Fri"), not nth-weekday-of-month patterns — reimplementing that logic client-side risked silently drifting from the real engine over time.

**Q45. Walk through the camera-capture bug — a good multi-attempt debugging story.**
A real device threw a bare "Failed to capture image" with no further detail. Three escalating theories, each explicitly ruled out by live re-testing: (1) React Navigation keeps tab screens mounted in the background, so a stale camera session was suspected — fixed with focus-gated mounting; same error persisted. (2) CameraX's `onCameraReady` firing before the capture pipeline actually binds — added a timing grace period; the user waited 10 minutes with zero change, which ruled out any timing-based theory outright. (3) Traced the literal error string into `expo-camera`'s own Android source and confirmed it's CameraX's own generic `ImageCaptureException`, with no further detail obtainable from JS — a real device/CameraX incompatibility, not a race. The actual fix: abandon `expo-camera`'s in-app preview entirely for `expo-image-picker`'s `launchCameraAsync()`, which just delegates to the phone's own stock camera app via an OS intent — worked immediately.

**Q46. GPS accuracy caused two separate but related bugs — what were they?**
A phone's GPS drifts 5–15m between readings of the literal same physical sign. (1) Sign grouping used exact-match keying (lat/lng rounded to ~1m precision) — a re-scan of one sign split into a *separate* "sign" entry the moment drift exceeded that. Fixed with proximity clustering (15m radius) instead of exact match. (2) The Nearby map fed one marker per location CLUSTER (reusing the cluster's own shared reference point), not one per actual scan — "3 signs near you" showed only 1 pin. Fixed by flat-mapping over each cluster's own scans, each plotted at its own real GPS reading.

**Q47. How does the system tell "two panels of one sign" apart from "two genuinely distinct nearby signs"?**
Content matching, not proximity: `Rule.describesSameRegulation` compares rule class, day pattern, time windows, and the type-specific field — deliberately ignoring rule id, description, holiday policy, and direction, which legitimately vary between two independent reads of the same real-world regulation. A re-scan of the same physical sign gets its matching rules *superseded* (old row deleted, new one written); genuinely different content within a few metres survives as a separate entry.

**Q48. Why did a fixed-radius "supersede nearby" approach get replaced twice before landing on content matching?**
V1 reused the `/check` answer radius (25m) for deletion too — dangerous, because 25m is tuned to be generous on the *forgiving* side (answering) not the *destructive* side (deleting), and two genuinely different signs commonly stand within 25m of each other. Tightened to a dedicated 8m radius. Even that proved unsafe: a live test showed two genuinely different signs scanned from nearly the same phone position landing at near-identical GPS, and the 8m proximity-only rule deleted a real 3-panel sign the instant a second, unrelated sign was scanned nearby. GPS accuracy and real sign spacing are simply too close in magnitude for *any* fixed radius to safely distinguish "same sign, re-read" from "different sign, nearby" — replaced entirely with content matching (Q47), where proximity is no longer a deletion criterion at all.

**Q49. What's the confidence gate, and why wasn't it actually enforced for a while?**
The stated philosophy ("low confidence → retake, never confidently wrong") was aspirational, not enforced, until a real multi-panel sign test exposed the gap: schema/semantic validation only checked *structural* shape, so a blurry photo that still happened to parse into valid JSON sailed through as a fully-trusted verdict. Fixed by having the semantic validator reject (force a retry) any extraction with confidence below 0.7 — closing the gap between what the docs claimed and what the code actually did.

---

## J. Feature Expansion, Admin Tooling & Product Decisions

**Q50. Why is History/Favorites 100% client-side (AsyncStorage), with zero backend sync?**
No real user-account system exists anywhere in the app — a deliberate scope decision, not an oversight. Building server-synced history would mean inventing accounts for a feature that doesn't strictly require one. The stated tradeoff: each device (or, on the web build, each browser) has a completely separate History; nothing syncs across devices; a reinstall or clearing browser storage loses it. Real cross-device history would need actual accounts + a backend table + sync logic — a distinctly bigger feature, not a quick add.

**Q51. How does "report an issue" work, and why can't a bad-faith report ever take a rule down?**
`POST /report` persists a `RuleReport` (rule_id, reason, device_id, timestamp) — and that is *all* it does. It can never automatically mutate or remove the rule it points at; it exists purely so a human can review it later. Deliberate safety property: an automatic "N reports = delist" mechanism would be trivially abusable to take down a real, correct regulation out of spite or error.

**Q52. What's the auth model behind the admin reports screen, and why not a real login system?**
Given no user-account system exists anywhere else in the app, building one just for this would be disproportionate. Instead, `GET /reports` is gated by a single shared secret checked against an SSM parameter (`PARKABLE_ADMIN_SECRET`), sent as an `X-Admin-Secret` header. Crucially, it's **fail-closed**: a missing or wrong secret returns 403, and a *missing configuration value* also returns 403 rather than silently allowing everyone through. The mobile Admin screen just asks for that secret once and remembers it locally.

**Q53. Why did photo threading through `/check` and `/nearby` become necessary later, when it wasn't in the original scope?**
The original mobile-feature scope only returned `photo_url` from `/scan` (a fresh extraction already has the photo bytes in hand). Once scanning-then-rechecking became routine on a real phone, "which physical sign is this verdict about?" (`/check`) and "what did I actually scan?" (`/nearby`, reviewing data as the app owner) turned into real, felt gaps rather than hypothetical ones. Fixed by extending the stored-rule record with a photo reference end-to-end and adding a resolver that presigns a fresh S3 URL per request — a 1-hour expiry, not S3's 7-day max, because presigning *inside* Lambda uses the execution role's own short-lived temporary credentials — gated to camera-scanned rows only (a government-data row's "photo reference" is a meaningless reused id; resolving one would just be a dead link).

**Q54. Describe the Check-tab "merging distinct signs" bug and its fix — good architecture-tradeoff story.**
`/check` originally pooled *every* stored rule within its 25m radius into one `RulesEngine.evaluate()` call. If two unrelated signs both happened to sit within 25m of each other (common on a real block), their rules got blended as though they were one sign's full rule set — the most-restrictive-wins ranking could silently pick a rule from the *wrong* sign and hide the other sign's actual situation entirely. Fixed by grouping stored rules by their originating sign (using a stable per-scan/per-record id) *before* evaluating: a multi-panel single scan's rules correctly stay together (they share that id), while genuinely distinct scans or government records always have distinct ids. One sign in range → today's single-verdict response, unchanged; multiple distinct signs → a per-sign verdict array, rendered as a small carousel on the Check screen — the same visual pattern already used on the Nearby screen for the analogous ambiguity.

---

## K. CI/CD, Deployment & Real-World Ops

**Q55. What does the CI/CD pipeline actually do, end to end?**
Two GitHub Actions workflows. `backend.yml`: a `test` job runs the full Maven suite on every push/PR touching the backend; a separate `deploy` job (`needs: test`) only fires on a manual `workflow_dispatch` trigger — packages the jar, then runs the same `aws cloudformation package`/`deploy` commands that were previously run by hand from a terminal. `mobile.yml`: typecheck plus `npx expo export --platform web` on every push/PR touching mobile code, needing no secrets and doing no deploy.

**Q56. Why a manual `workflow_dispatch` trigger instead of GitHub's "required reviewers" environment protection?**
The "proper" approach was tried first — a `production` GitHub Environment with a required reviewer, giving a one-click Approve gate. The live attempt to create it via the API returned a 422: required-reviewer environment protection needs a paid plan on a *private* repository (it's free on public repos, which is why this wasn't obvious ahead of time). Rather than pay for a plan or make the repo public just for this one feature, switched to a manual `workflow_dispatch` trigger instead — it gives the identical guarantee ("nothing reaches AWS without a deliberate action"), for free, at the cost of a slightly less polished UI (a button press instead of an approval banner).

**Q57. How do you actually know the AWS backend is cheap, rather than just assuming?**
Pulled real numbers from AWS Cost Explorer instead of guessing: **$0.0028 for the month**, comfortably inside the 12-month Free Tier (1M free Lambda requests/month, 1M free API Gateway calls/month for the first year, 5GB free S3 storage). Also worth knowing: redeploying doesn't add cost by itself — a CloudFormation/Lambda code update is essentially free; the only genuinely usage-based cost is real traffic, which is identical whether that code was pushed by a human from a terminal or by a CI robot.

**Q58. How is the mobile web build deployed, and why that platform?**
Vercel, connected directly to the GitHub repo, with the build intentionally rooted one directory down (`mobile/`) via explicit build/install/output-directory overrides rather than a monorepo "Root Directory" dashboard setting the CLI version in use didn't expose a flag for — worked around with `cd mobile && npx expo export --platform web` as the literal build command. Every push to `main` auto-builds and redeploys; verified live by pushing a real code change and watching it get picked up and deployed automatically within about a minute.

**Q59. What is a PWA, and what did adding one actually involve technically?**
A Progressive Web App: a website installable to a phone's home screen (via a `manifest.json` + icon set) that then launches full-screen with no browser address bar, without ever going through an app store. Expo's Metro web exporter auto-generates a favicon and a theme-color meta tag from `app.json`, but not a manifest — confirmed, by deliberately testing with a marker file, that a hand-written `public/index.html` fully overrides Expo's generated template, and that Expo still correctly re-injects the content-hashed JS bundle `<script>` tag regardless of what the custom template contains — meaning the hand-written template can never go stale when the bundle's hash changes on a later build. Also generated placeholder app icons with Python/Pillow, since no image-generation tool otherwise existed in the environment and the project had no icon at all up to that point.

---

<!-- ================================================================
CHECKPOINT — for Claude Code, do not delete or edit by hand.
Covered so far: project start → Phase 1 (A-F) → Phase 2 AWS backend (G)
  → Phase 2.5 gov data ETL, multi-city (H) → Phase 3 mobile app +
  real-device debugging saga (I) → feature expansion, admin tooling,
  Check multi-sign fix (J) → CI/CD, GitHub push, Vercel/PWA deploy (K).
Last question: Q59
Next update: resume from whatever lands after this point (e.g. web
  map fallback, real user accounts / cross-device sync, evals/Phase 4,
  EAS/app-store builds if ever pursued).
================================================================ -->
