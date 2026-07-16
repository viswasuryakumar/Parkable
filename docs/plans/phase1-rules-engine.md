# Parkable — Phase 1: Core Rules Engine + LLM Extraction Interface + Local CLI

**Status**: Architecture planned, ready for implementation in 3 stages (A, B, C).

This document is the authoritative specification for Phase 1 implementation. It stands alone — agents reading this file have full context: package layout, JSON schema, domain model, engine algorithm, extraction/validation flow, CLI design, ordered build steps, and concrete test scenarios.

---

## 1. Maven Project Structure

```
Parkable/
  docs/
    schema.md
    plans/phase1-rules-engine.md
  backend/
    pom.xml
    src/main/java/com/parkable/
      model/
        Rule.java                          # sealed interface
        RuleMetadata.java                  # record
        NoParkingRule.java                 # record implements Rule
        TimeLimitRule.java                 # record implements Rule
        PermitRule.java                    # record implements Rule
        TimeWindow.java                    # record; owns midnight-crossing logic
        DayPattern.java                    # sealed interface
        SpecificDays.java                  # record implements DayPattern
        NthWeekdayOfMonth.java             # record implements DayPattern (with -1 = last)
        DirectionalModifier.java           # record
        Side.java                          # enum: LEFT, RIGHT, NONE
        ArrowDirection.java                # enum: NORTH, SOUTH, EAST, WEST, NONE
        HolidayPolicy.java                 # record
        Verdict.java                       # enum: PARKABLE, NOT_PARKABLE, DEPENDS
        RuleMatch.java                     # record: rule + reason string
        VerdictResult.java                 # record: verdict + Optional<RuleMatch> + Optional<Instant> validUntil + List<String> trace
      calendar/
        HolidayCalendar.java               # interface
        UsFederalHolidayCalendar.java      # implementation (fixed + floating + weekend-observed)
      engine/
        RulesEngine.java                   # public API: evaluate(rules, instant, zone, observerSide)
        TemporalRuleEvaluator.java         # single-rule activation + next-boundary logic
        RuleActivation.java                # record returned by evaluator
      builder/
        RuleBuilder.java                   # fluent builder for tests
      factory/
        RuleFactory.java                   # JSON DTO -> concrete Rule subtype
      extraction/
        VisionExtractor.java               # interface
        ImageInput.java                    # record: bytes, mediaType, sourceReference
        ExtractionResult.java              # sealed interface: Success | NeedsReview
        ExtractedSignData.java             # DTO mirroring docs/schema.md
        ExtractionMetadata.java            # record: photoReference, parserVersion, extractedAt
        ClaudeVisionExtractor.java         # real implementation (HttpClient -> Anthropic API)
        FixtureVisionExtractor.java        # offline stub (reads local JSON fixture)
        ValidatingRetryingVisionExtractor.java  # decorator: validate -> retry -> NeedsReview
        VisionExtractionException.java     # unchecked, for transport/IO failures only
      validation/
        RuleJsonSchemaValidator.java       # wraps networknt json-schema-validator
        SemanticRuleValidator.java         # cross-field validation
        ValidationResult.java              # record: valid, List<String> errors
      datasource/
        SignSource.java                    # interface (seam for Phase 2.5)
        CameraScanSignSource.java          # adapter: wraps VisionExtractor as SignSource
      repository/
        RuleRepository.java                # interface (seam for Phase 2)
        InMemoryRuleRepository.java        # trivial in-memory impl
        ExtractionRecord.java              # record: stores extraction + metadata
      cli/
        ScanCLI.java                       # main() + testable run(String[], Clock)
        CliArgs.java                       # record: parsed arguments
        OutputFormatter.java               # verdict/trace -> human-readable text
    src/main/resources/schema/
      parking-rule-schema.json             # JSON Schema 2020-12 (validates parsed extraction)
    src/test/java/com/parkable/
      (mirrors main tree structure; bulk of tests in engine/)
    src/test/resources/
      fixtures/*.json                      # sample extraction JSON (valid + invalid)
      images/*.jpg                         # 1-2 tiny placeholder images
```

### pom.xml: Key Dependencies & Config

```xml
<properties>
  <maven.compiler.release>21</maven.compiler.release>
  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>

<dependencies>
  <!-- Production -->
  <dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.16.1</version>
  </dependency>
  <dependency>
    <groupId>com.networknt</groupId>
    <artifactId>json-schema-validator</artifactId>
    <version>1.0.91</version>
  </dependency>
  
  <!-- Test -->
  <dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.9.2</version>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <version>3.24.1</version>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <version>5.2.0</version>
    <scope>test</scope>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-surefire-plugin</artifactId>
      <version>2.22.2</version>
    </plugin>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-shade-plugin</artifactId>
      <version>3.5.0</version>
      <executions>
        <execution>
          <phase>package</phase>
          <goals><goal>shade</goal></goals>
          <configuration>
            <transformers>
              <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                <mainClass>com.parkable.cli.ScanCLI</mainClass>
              </transformer>
            </transformers>
            <finalName>parkable-cli</finalName>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

No AWS SDK, Postgres driver, Expo, or Spring — out of scope for Phase 1.

---

## 2. JSON Rule Schema (docs/schema.md)

The schema is the contract between LLM extraction and the rules engine. Write this **before** any code.

### Extraction Envelope

```jsonc
{
  "extraction_id": "string (uuid)",
  "photo_reference": "string",          // local file path in Phase 1; S3 key in Phase 2
  "parser_version": "string",           // e.g., "claude-vision-extractor-v1" for reproducibility
  "extracted_at": "string (ISO-8601)",  // e.g., "2026-07-14T18:04:00Z"
  "confidence": 0.0,                    // 0.0-1.0, overall extraction confidence
  "raw_text": "string",                 // best-effort OCR dump for audit/debugging
  "rules": [ /* array of RuleJson, min 1 */ ]
}
```

### RuleJson — One per distinct rule on the sign

```jsonc
{
  "rule_id": "string (unique within extraction)",
  "sign_type": "NO_PARKING | TIME_LIMIT | PERMIT_REQUIRED | STREET_CLEANING",
  "description": "string",              // human-readable text as printed on sign
  "restriction": {
    "time_limit_minutes": 120           // required iff sign_type == TIME_LIMIT
    // OR
    "permit_zone": "A"                  // required iff sign_type == PERMIT_REQUIRED
  },
  "time_window": {
    "all_day": false,
    "start": "20:00",                   // HH:mm, 24h format; required unless all_day=true
    "end": "02:00"                      // may be numerically < start = midnight-crossing
  },
  "day_pattern": {
    "type": "SPECIFIC_DAYS | NTH_WEEKDAY_OF_MONTH",
    "days": ["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"],
    // OR
    "weekday": "TUESDAY",
    "occurrences": [1, 3]               // 1-4 for nth, -1 for "last"
  },
  "holiday_policy": {
    "suspended_on_holidays": true,
    "calendar": "US_FEDERAL"
  },
  "direction": {
    "side": "LEFT | RIGHT | NONE",
    "arrow": "NORTH | SOUTH | EAST | WEST | NONE"
  }
}
```

### Key Design Notes (for docs/schema.md)

- **"ANY TIME" composition**: no separate enum value; instead `all_day=true` + all-7-days pattern.
- **STREET_CLEANING**: reuses the same domain shape as NO_PARKING (just different day pattern + description) — not a separate Rule subtype, to avoid premature splitting.
- **Missing time_window.end validation**: when `all_day=false`, a missing `end` is a **validation error** in camera-scan extraction (never silently guessed as 24h) — this is stricter than gov-data ETL will be in Phase 2.5, and the asymmetry should be noted.
- **Reproducibility contract**: `photo_reference` + `parser_version` + `extracted_at` exist on the envelope so even though Phase 1 doesn't persist to a real database, the DTO shape carries the metadata needed for reproducibility (store photo + extracted JSON + parser_version + timestamp → reprocess old scans when extraction improves).

---

## 3. Domain Model & Design Patterns

### Philosophy: Composition over Inheritance

Every concrete `Rule` *has-a* `RuleMetadata` rather than extending a base class.

```java
public record RuleMetadata(
    String ruleId,
    String description,
    DayPattern dayPattern,
    Optional<TimeWindow> timeWindow,     // empty = ANY_TIME
    HolidayPolicy holidayPolicy,
    DirectionalModifier direction
) {}

public sealed interface Rule permits NoParkingRule, TimeLimitRule, PermitRule {
    RuleMetadata metadata();
}

public record NoParkingRule(RuleMetadata metadata) implements Rule {}
public record TimeLimitRule(RuleMetadata metadata, Duration limit) implements Rule {}
public record PermitRule(RuleMetadata metadata, String permitZone) implements Rule {}
```

### Core Value Types

```java
// TimeWindow: owns midnight-crossing logic independently
public record TimeWindow(LocalTime start, LocalTime end) {
    public boolean contains(LocalTime t) {
        boolean crossesMidnight = end.isBefore(start);
        return crossesMidnight
            ? !t.isBefore(start) || t.isBefore(end)   // 11pm-2am: t >= 11pm OR t < 2am
            : !t.isBefore(start) && t.isBefore(end);  // normal: t >= start AND t < end
    }
    public boolean crossesMidnight() { return end.isBefore(start); }
}

// DayPattern: sealed hierarchy for type safety
public sealed interface DayPattern permits SpecificDays, NthWeekdayOfMonth {
    boolean matches(LocalDate date);
}

public record SpecificDays(Set<DayOfWeek> days) implements DayPattern {
    public boolean matches(LocalDate date) {
        return days.contains(date.getDayOfWeek());
    }
}

public record NthWeekdayOfMonth(DayOfWeek weekday, Set<Integer> occurrences) implements DayPattern {
    // occurrences: {1,2,3,4} for 1st-4th occurrence, or {-1} for "last"
    public boolean matches(LocalDate date) {
        if (!date.getDayOfWeek().equals(weekday)) return false;
        int dayOfMonth = date.getDayOfMonth();
        int occurrence = (dayOfMonth - 1) / 7 + 1;  // 1st-4th
        if (dayOfMonth > 21) {  // possibly "last"
            LocalDate nextWeek = date.plusDays(7);
            if (nextWeek.getMonth() != date.getMonth()) {
                return occurrences.contains(-1);  // -1 = last
            }
        }
        return occurrences.contains(occurrence);
    }
}

// HolidayCalendar: interface for testability/swappability
public interface HolidayCalendar {
    boolean isHoliday(LocalDate date);
}

public class UsFederalHolidayCalendar implements HolidayCalendar {
    // Fixed: Jan 1 (New Year), July 4 (Independence), Dec 25 (Christmas)
    // Floating: 3rd Mon (MLK), last Mon May (Memorial), 1st Mon Sept (Labor),
    //           4th Thurs Nov (Thanksgiving)
    // Weekend-observed: if holiday falls Sat, observed Fri; if Sun, observed Mon
}

// Verdict, RuleMatch, VerdictResult: immutable result types
public enum Verdict {
    PARKABLE,
    NOT_PARKABLE,
    DEPENDS  // ambiguous (directional conflict, permit unverifiable, etc.)
}

public record RuleMatch(Rule rule, String reason) {}

public record VerdictResult(
    Verdict verdict,
    Optional<RuleMatch> triggeringRule,
    Optional<Instant> validUntil,         // empty = unbounded/no known boundary
    List<String> trace                    // step-by-step reasoning for audit
) {}
```

### Design Patterns Applied

1. **Builder** (`RuleBuilder`): Fluent construction for tests. *Not* used by production parsing code (that goes through `RuleFactory`).
   ```java
   Rule rule = new RuleBuilder()
       .withType(NO_PARKING)
       .onDays(MONDAY, TUESDAY)
       .duringWindow(LocalTime.of(20, 0), LocalTime.of(2, 0))
       .build();
   ```

2. **Factory** (`RuleFactory`): JSON DTO → concrete `Rule` subtype. Validates consistency; fails fast on unsupported `sign_type`.

3. **Strategy** (`VisionExtractor`): interface for swappable extraction providers (Claude, Google, etc.). Zero changes to the engine to add a new provider.

4. **Adapter** (`ClaudeVisionExtractor`): wraps external Anthropic API shape into domain model.

5. **Decorator** (`ValidatingRetryingVisionExtractor`): wraps `VisionExtractor` + validation + retry logic. Composes cleanly over different implementations.

6. **Repository** (`RuleRepository` interface + `InMemoryRuleRepository`): seam for Phase 2 storage swap (Postgres, MongoDB, etc.) without touching the engine.

---

## 4. RulesEngine Algorithm

The engine is split into two classes for SRP:

### TemporalRuleEvaluator: Single-Rule Activation Logic

Answers, for one rule at one instant: is it active, and when does that answer next change?

```java
public class TemporalRuleEvaluator {
    private final HolidayCalendar holidays;
    
    public RuleActivation evaluate(Rule rule, ZonedDateTime at) {
        // Returns: (isActive, nextBoundary, reason)
    }
}

public record RuleActivation(
    boolean active,
    Optional<Instant> nextBoundary,
    String reason
) {}
```

**Algorithm** (pseudo-code, with WHY-comments for non-obvious temporal logic):

```
1. Holiday Suspension Check
   IF rule.holidayPolicy.suspendedOnHolidays AND calendar.isHoliday(at.toLocalDate())
     THEN rule is inactive for this calendar day
          nextBoundary candidate = midnight starting the next calendar day (when holiday status can change)

2. Day Pattern Match for "Today"
   IF rule.dayPattern.matches(at.toLocalDate()) AND NOT holiday-suspended
     IF rule has NO time window ("ANY_TIME")
       THEN rule is active
            Forward-scan day-by-day (bounded, MAX_LOOKAHEAD = 366) until day pattern stops matching
                 or holiday suspension starts
            nextBoundary = midnight of first non-matching day
     IF rule has a time window
       THEN build "today's window instance" = [start time today, end time today]
            (or [start today, end tomorrow] if window crosses midnight)
            IF at.toLocalTime() falls within this window
              THEN rule is active
                   nextBoundary = window end
              ELSE rule is inactive (within today's pattern but outside window)
                   nextBoundary = window start (if later today) or tomorrow's window start

3. **Midnight-Crossing Yesterday-Tail** (the subtle case naive implementations miss)
   IF rule.dayPattern.matches(at.toLocalDate().minusDays(1))
      AND NOT holiday-suspended on yesterday
      AND rule has a time window that crosses midnight
      AND at.toLocalTime().isBefore(window.end)
     THEN rule is ALSO active via yesterday's window spilling into today
          nextBoundary = this window's end

4. NOT Currently Active Case
   IF rule.dayPattern does NOT match today AND no yesterday-tail spillover
     THEN rule is inactive
          Forward-scan (bounded, MAX_LOOKAHEAD = 366) for the next day when pattern matches
               (accounting for holidays)
          nextBoundary = that day's window start (or midnight if ANY_TIME)

5. DST & Boundary Construction
   All ZonedDateTime construction via ZonedDateTime.of(LocalDateTime, ZoneId)
   JDK default DST gap/overlap resolution applied uniformly
   Tests pin behavior at spring-forward (2:00am skips to 3:00am) and
   fall-back (1:00am repeats) edges → any future JDK/zone-data change surfaced as failing test

6. Defensive Degenerate Case
   If day pattern is empty or malformed (no matching days ever)
     THEN return inactive / no boundary, rather than infinite loop
```

### RulesEngine: Multi-Rule Aggregation

```java
public class RulesEngine {
    public VerdictResult evaluate(
        List<Rule> rules,
        Instant instant,
        ZoneId zone,
        Optional<Side> observerSide
    ) {
        ZonedDateTime at = instant.atZone(zone);
        
        // 1. Run TemporalRuleEvaluator per rule -> list of (rule, RuleActivation)
        List<RuleWithActivation> allEvaluations = rules.stream()
            .map(r -> new RuleWithActivation(r, evaluator.evaluate(r, at)))
            .collect(toList());
        
        // 2. Keep only active rules
        List<Rule> activeRules = allEvaluations.stream()
            .filter(ra -> ra.activation.active())
            .map(ra -> ra.rule)
            .collect(toList());
        
        // 3. Check for directional conflict -> DEPENDS
        // DEPENDS only if ≥2 active rules have distinct non-NONE sides AND
        // they would yield different verdicts AND no observerSide given
        if (shouldResolveSideAmbiguity(activeRules, observerSide)) {
            // Filter to matching-side or NONE before re-evaluating
            activeRules = filterBySide(activeRules, observerSide.get());
        } else if (hasDirectionalConflict(activeRules)) {
            return new VerdictResult(DEPENDS, Optional.empty(), nextBoundary(...), trace);
        }
        
        // 4. Most-restrictive-wins ranking
        // NOT_PARKABLE(3) > DEPENDS-permit(2) > TimeLimitRule(1) > nothing(0)
        Rule triggeringRule = rankAndPick(activeRules);
        Verdict verdict = verdict(triggeringRule);
        
        // 5. validUntil = soonest nextBoundary across ALL rules (active or not)
        Optional<Instant> validUntil = allEvaluations.stream()
            .flatMap(ra -> ra.activation.nextBoundary())
            .min(Instant::compareTo);
        
        List<String> trace = buildTrace(allEvaluations, verdict, triggeringRule);
        
        return new VerdictResult(verdict, Optional.of(new RuleMatch(triggeringRule, reason)), validUntil, trace);
    }
}
```

**Key Points**:
- **No `Clock.now()`**: `instant` is always a parameter. The only place `Instant.now()` is legitimate is the CLI's entry boundary (`main()` method).
- **All-rules boundary**: `validUntil` considers inactive rules too — "when could the answer change" includes an inactive rule about to activate.
- **Directional DEPENDS**: Only fires when it *matters* (different verdicts from different sides). Two active rules agreeing on the outcome despite differing sides do not trigger DEPENDS (avoids manufacturing false ambiguity).
- **Trace list**: Every decision step logged for audit + debugging.

---

## 5. Extraction + Validation Flow

### VisionExtractor Interface

```java
public interface VisionExtractor {
    /// Extracts parking rules from an image.
    /// Returns structured JSON; NEVER decides a verdict (engine's job).
    /// Throws VisionExtractionException on genuinely exceptional failures (network, malformed response).
    ExtractionResult extract(ImageInput image);
}

public record ImageInput(byte[] bytes, String mediaType, Path sourceReference) {}

public sealed interface ExtractionResult permits ExtractionResult.Success, ExtractionResult.NeedsReview {
    record Success(ExtractedSignData data, ExtractionMetadata metadata) implements ExtractionResult {}
    record NeedsReview(String message, ExtractionMetadata metadata) implements ExtractionResult {}
}

public record ExtractionMetadata(String photoReference, String parserVersion, Instant extractedAt) {}
```

`ExtractionResult` is a sealed result type, *not* an exception. `NeedsReview` is a first-class expected outcome ("couldn't read clearly"), not exceptional control flow. `VisionExtractionException` is reserved for genuinely unexpected failures (network I/O, completely malformed HTTP response).

### Three Implementations (Composition via Strategy + Decorator)

1. **FixtureVisionExtractor** — Build this first
   - Reads pre-recorded JSON fixture (by filename convention: `sign.jpg` → look up `sign.json`)
   - Fully offline, no API key needed
   - Unblocks CLI, engine, and integration tests before real API call is wired
   - Returns `Success(extracted, metadata)` with canned data

2. **ClaudeVisionExtractor** — Real implementation
   - Uses `java.net.http.HttpClient` (JDK built-in, no extra dependency)
   - Calls Anthropic Messages API with image content block (base64) + text prompt
   - Prompt instructs model to return only JSON conforming to `docs/schema.md`
   - API key from env var (e.g., `ANTHROPIC_API_KEY`), never hardcoded
   - Model ID should be pulled from a single named constant (e.g., `CLAUDE_VISION_MODEL`)
   - **Confirm exact current Anthropic Messages API shape at implementation time** rather than hard-coding from this plan
   - On transport/parse failure at HTTP layer: throw `VisionExtractionException` (exceptional case)
   - Non-JSON or schema-invalid response body: flows into normal validate → retry → NeedsReview path (expected outcome, not exception)

3. **ValidatingRetryingVisionExtractor** — Decorator wrapping any delegate
   - Call delegate → validate JSON schema → if invalid, retry once → if still invalid, `NeedsReview`
   - Works identically against `FixtureVisionExtractor` (useful for testing retry logic) and `ClaudeVisionExtractor`
   - Satisfies Open/Closed: adding a third provider later needs zero changes here
   - Validation happens inside the decorator, not in the extractor

### Validation (Two-Stage)

1. **Structural Validation** — `RuleJsonSchemaValidator`
   - Runs raw Jackson `JsonNode` against `backend/src/main/resources/schema/parking-rule-schema.json`
   - Uses `networknt/json-schema-validator` (actively maintained, integrates cleanly with Jackson)
   - Covers: required fields, types, enums, conditional rules (`if sign_type == TIME_LIMIT then require time_limit_minutes`)

2. **Semantic Validation** — `SemanticRuleValidator`
   - Catches anything awkward to express in JSON Schema: `start == end` ambiguity, invalid `occurrences` values, empty day sets, `rule_id` uniqueness within extraction
   - Returns `ValidationResult(boolean valid, List<String> errors)` — both validators compose into one check list
   - Does not throw; failures flow into retry orchestration

### ValidatingRetryingVisionExtractor Flow

```java
public class ValidatingRetryingVisionExtractor implements VisionExtractor {
    private final VisionExtractor delegate;
    private final RuleJsonSchemaValidator schemaValidator;
    private final SemanticRuleValidator semanticValidator;
    
    public ExtractionResult extract(ImageInput image) {
        // Attempt 1
        ExtractionResult first = delegate.extract(image);
        if (first instanceof ExtractionResult.Success success) {
            ValidationResult validation = validate(success.data());
            if (validation.valid()) {
                return first;
            }
            // Invalid: prepare for retry
        }
        
        // Attempt 2 (if first was NeedsReview or validation failed)
        ExtractionResult second = delegate.extract(image);
        if (second instanceof ExtractionResult.Success success) {
            ValidationResult validation = validate(success.data());
            if (validation.valid()) {
                return second;
            }
        }
        
        // Both attempts failed validation or returned NeedsReview
        return new ExtractionResult.NeedsReview(
            "Couldn't read this sign clearly. Please retake with the full sign visible and in focus.",
            metadata
        );
    }
    
    private ValidationResult validate(ExtractedSignData data) {
        ValidationResult schema = schemaValidator.validate(data);
        if (!schema.valid()) return schema;
        return semanticValidator.validate(data);
    }
}
```

Specific validation errors go to trace/logs for debugging; the user-facing message stays generic/honest (never tries to explain JSON Schema to an end user).

### RuleFactory

```java
public class RuleFactory {
    public static Rule from(ExtractedSignData data) {
        return switch (data.signType()) {
            case NO_PARKING -> new NoParkingRule(buildMetadata(data));
            case TIME_LIMIT -> new TimeLimitRule(buildMetadata(data), Duration.ofMinutes(data.timeLimitMinutes()));
            case PERMIT_REQUIRED -> new PermitRule(buildMetadata(data), data.permitZone());
            case STREET_CLEANING -> new NoParkingRule(buildMetadata(data));  // same as NO_PARKING, different pattern
            default -> throw new UnsupportedSignTypeException(data.signType());
        };
    }
}
```

Only ever called on already-schema-and-semantically-valid data (`Success` case). Fails fast with a clear typed exception on truly unexpected `sign_type` values (not validation errors — those have already been caught).

### SignSource Interface (Seam for Phase 2.5)

```java
public interface SignSource {
    List<Rule> fetch(double latitude, double longitude, ZoneId zone);
}

public class CameraScanSignSource implements SignSource {
    private final VisionExtractor extractor;
    private final RuleFactory factory;
    
    public CameraScanSignSource(VisionExtractor extractor) {
        this.extractor = extractor;
        this.factory = new RuleFactory();
    }
    
    public List<Rule> fetch(double lat, double lng, ZoneId zone) {
        // Placeholder: Phase 1 uses extraction directly via CLI, not this interface.
        // Proves the seam exists; adding GovDataSignSource in Phase 2.5 won't touch the engine.
        throw new UnsupportedOperationException("Phase 1: camera scanning not yet integrated");
    }
}
```

Exists to prove the architecture seam is real. The CLI can call the extractor/factory directly for simplicity, but the presence of this interface satisfies the architecture rule ("SignSource + VisionExtractor: interfaces from day one").

---

## 6. Local CLI Design

### ScanCLI: Entry Point

```java
public class ScanCLI {
    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC()));
    }
    
    static int run(String[] args, Clock clock) {
        // Testable, accepts optional clock for reproducible tests
        CliArgs parsed = CliArgs.parse(args);
        
        // ... orchestration (see below)
        
        return exitCode;
    }
}
```

The single seam where `Instant.now()` is legitimate (the CLI's entry boundary), and deliberately kept out of `main()` directly so tests can call `run(...)` without spawning a subprocess.

### Arguments

- **Positional**: image path (required)
- `--now=<ISO-8601 instant>` (default: `Clock.systemUTC().instant()`)
- `--zone=<ZoneId>` (default: `America/Los_Angeles`, matching SF project focus)
- `--side=<LEFT|RIGHT>` (optional, feeds `observerSide` into `RulesEngine.evaluate`)
- `--extractor=stub|claude` (default `stub`, so jar runs offline out of the box)

### Orchestration

```
1. Read image bytes from path → ImageInput
2. Build extractor chain:
   delegate = --extractor == "stub" ? FixtureVisionExtractor : ClaudeVisionExtractor
   extractor = new ValidatingRetryingVisionExtractor(delegate, validator)
3. ExtractionResult result = extractor.extract(image)
4. Case NeedsReview:
     Print the honest message
     DO NOT call the engine
     Exit code 2
5. Case Success:
     RuleFactory.from(result.data()) → List<Rule>
     Optionally: InMemoryRuleRepository.save(new ExtractionRecord(...)) [exercises the seam]
     RulesEngine.evaluate(rules, instant, zone, observerSide) → VerdictResult
     OutputFormatter prints:
       - Verdict (PARKABLE / NOT_PARKABLE / DEPENDS)
       - Triggering rule id + description
       - validUntil converted to local time in zone (or "no expiration")
       - Full trace list
6. Exit code:
     0 = PARKABLE
     1 = NOT_PARKABLE
     2 = NEEDS_REVIEW
     3 = DEPENDS
```

Exit codes are deliberately distinct so the CLI is scriptable/testable (shell scripts can act on exit code).

---

## 7. Ordered Build Checklist (No Calendar, Sequence Only)

Each step → compiling build → passing tests before next step.

### Stage A: Project Skeleton + Domain Model + Temporal Engine (Build Steps 1–12)

1. Initialize Maven project skeleton (`pom.xml`: Java 21, JUnit 5, Jackson, networknt json-schema-validator, AssertJ, Mockito, shade plugin).
2. Confirm `mvn -q compile` succeeds on empty tree.
3. Write `docs/schema.md` in full (the contract).
4. Write `backend/src/main/resources/schema/parking-rule-schema.json` matching the doc.
5. **TDD `TimeWindow`**: tests first (`contains()`, `crossesMidnight()`, midnight-crossing edge cases), then implementation.
6. **TDD `DayPattern` hierarchy**: `SpecificDays` and `NthWeekdayOfMonth` (including `-1` "last" sentinel), edge cases for all 7 weekdays, month boundaries.
7. **TDD `HolidayCalendar`/`UsFederalHolidayCalendar`**: fixed holidays (Jan 1, July 4, Dec 25), floating holidays (3rd Mon, last Mon May, 1st Mon Sept, 4th Thurs Nov), weekend-observed shifting.
8. Add trivial classes: `DirectionalModifier`, `Side`, `ArrowDirection` (light tests).
9. Add: `RuleMetadata`, sealed `Rule` interface, concrete impls (`NoParkingRule`, `TimeLimitRule`, `PermitRule`).
10. Add: `RuleBuilder` (fluent, test-oriented).
11. **TDD `TemporalRuleEvaluator`**: the core temporal logic. Write the edge-case tests (see §9 below) **before** implementation. Focus on: same-day + midnight-crossing containment, weekday/nth-weekday matching, DST spring-forward/fall-back edges, bounded forward-scan, "yesterday-tail" spillover.
12. **TDD `RulesEngine`**: aggregation (most-restrictive-wins, directional DEPENDS handling, empty-rules-list, nextBoundary across all rules).

At end of Stage A: `mvn test` green, ≥25 tests passing, DST/midnight-crossing tests actually exercise the edge (not accidentally trivial).

### Stage B: Extraction + Validation (Build Steps 13–20)

13. Add `Verdict`, `RuleMatch`, `VerdictResult`.
14. Fill out `ExtractedSignData` DTOs + Jackson mapping mirroring the schema.
15. **Implement `RuleFactory`** (JSON DTO → concrete `Rule`), + tests (unsupported-type fail-fast).
16. **Implement `RuleJsonSchemaValidator`** (networknt against JSON schema resource) + tests (valid/missing-field fixtures).
17. **Implement `SemanticRuleValidator`** (cross-field checks) + tests.
18. Define `VisionExtractor`, `ImageInput`, `ExtractionResult` (sealed), `ExtractionMetadata`.
19. **Implement `FixtureVisionExtractor`** (reads local JSON fixtures) + tests. Unblocks offline CLI use.
20. **Implement `ValidatingRetryingVisionExtractor`** decorator + tests (mocked delegate: fail-then-succeed, always-fail cases).

At end of Stage B: `mvn test` green, ≥40 tests total, `FixtureVisionExtractor` + sample fixture JSON round-trip through `RuleFactory` confirmed correct.

### Stage C: CLI + Packaging (Build Steps 21–23)

21. **Implement `ClaudeVisionExtractor`** (real HTTP call to Anthropic API). Confirm current Messages API model ID/shape against docs at this point. Guard behind env var so it's skippable in CI without a key.
22. **Implement `RuleRepository` interface + `InMemoryRuleRepository` + `ExtractionRecord`**. Implement `SignSource`/`CameraScanSignSource` seam.
23. **Implement `ScanCLI`** (thin `main()` + testable `run(String[], Clock)`) + `OutputFormatter` + end-to-end fixture-driven tests.
24. Wire `maven-shade-plugin`; verify `mvn clean package` yields `target/parkable-cli.jar`.
25. Verify: `java -jar target/parkable-cli.jar src/test/resources/images/sign.jpg --now=2026-07-14T18:04:00Z --extractor=stub` runs end-to-end, prints verdict + trace, exits with correct code.
26. Full `mvn test` pass (≥50 tests).
27. Architecture review: confirm code satisfies the four rules in `CLAUDE.md` (no Lambda, SignSource/VisionExtractor as interfaces, instant always a parameter, every extraction carries source/version).

At end of Stage C: `target/parkable-cli.jar` runs end-to-end; all tests green.

---

## 8. Test Scenarios: 35+ Concrete Cases

Weighted heavily toward `TemporalRuleEvaluator`/`RulesEngine` since that's where the hard problems live.

### TimeWindow / DayPattern (5 cases)
1. Same-day window: time inside, before start, after end, exactly at start (inclusive), exactly at end (exclusive).
2. Midnight-crossing window (11pm–2am): 11:30pm inside, 1am inside, 3am outside, 10:59pm outside.
3. SpecificDays weekdays-only (Mon–Fri) matches weekdays, excludes Sat/Sun.
4. NthWeekdayOfMonth "1st & 3rd Tuesday" in 5-Tuesday month matches 1st/3rd, not 2nd/4th/5th.
5. NthWeekdayOfMonth "last Friday" (-1 sentinel) across a 4-Friday month and a 5-Friday month.

### Holiday Calendar (4 cases)
6. Fixed-date holiday (July 4) suspends a rule that day.
7. Floating holiday (Thanksgiving = 4th Thursday Nov) computed correctly for a year.
8. Weekend-observed shift: holiday on Saturday shifts to Friday; on Sunday to Monday.
9. `holidayPolicy.suspendedOnHolidays=false` — rule still applies on a holiday.

### TemporalRuleEvaluator (14 cases)
10. ANY_TIME all-days rule — always active, `nextBoundary` empty (unbounded).
11. TimeLimitRule active mid-window → boundary = window end today.
12. Rule inactive because window hasn't started yet today → boundary = window start today.
13. Rule inactive because day pattern doesn't match today (Tue-only rule on Wed) → boundary = next Tue's window start.
14. **Midnight-crossing yesterday-tail**: Monday-only rule, window 11pm–2am, checked Tuesday 1am → still active via Mon-night window.
15. Nth-weekday + time-window combo (1st/3rd Tue 8–10am): inactive on non-qualifying Tue, active on qualifying one, boundary jumps weeks.
16. DST spring-forward: window crossing the nonexistent 2:00–3:00am → construction deterministic, tests pin exact boundary.
17. DST fall-back: window boundary near repeated 1:00–2:00am → no boundary earlier than evaluated instant.
18. Bounded forward-scan: empty day pattern never matches → returns "no occurrence within lookahead," no hang.
19. Multiple day patterns on same rule (shouldn't happen in JSON, but test aggregation).
20. Very far-future window start (requires >366-day scan) → stops at lookahead boundary.
21. Window with zero duration (start == end) → schema validation should catch, but test engine's defensiveness.
22. Timezone boundary: rule at same absolute instant but different zones.
23. Leapyear handling (Feb 29).

### RulesEngine Aggregation (11 cases)
24. Single active NO_PARKING → NOT_PARKABLE, correct triggering rule.
25. Single active TIME_LIMIT, nothing else → PARKABLE with limit noted in trace.
26. No rules active right now → PARKABLE, `validUntil` = soonest boundary among all rules.
27. Inactive NO_PARKING + active TIME_LIMIT → PARKABLE (only active rules count).
28. Simultaneously active NO_PARKING + TIME_LIMIT → NOT_PARKABLE wins (most-restrictive), trace mentions both.
29. Active PERMIT_RULE, no side info → DEPENDS ("permit required").
30. Directional conflict: active LEFT-forbids + active RIGHT-allows, no `observerSide` → DEPENDS.
31. Same conflict, `observerSide=RIGHT` supplied → resolves to RIGHT-applicable rule's verdict.
32. Both-sides-agree case (LEFT and RIGHT both NOT_PARKABLE) → NOT_PARKABLE even without side info (DEPENDS not triggered).
33. Fully empty rule list → PARKABLE, `validUntil` empty, trace explains no rules.
34. Multiple PermitRules (different zones) both active, both DEPENDS → pick first in trace order.

### Validation / Extraction (4 cases)
35. Fully valid JSON fixture → passes schema + semantic validation → maps to Rule correctly (golden test).
36. Missing required field (`sign_type` absent) → schema validation fails → retry path.
37. Retry succeeds on 2nd delegate call → `Success` returned (Mockito delegate fail-once).
38. Retry fails both calls → `NeedsReview` with honest message; engine never invoked.
39. Unsupported `sign_type` value → `RuleFactory` throws typed exception (fail-fast).

### CLI / End-to-End (3+ cases)
40. Fixture image + fixed `--now` + known fixture JSON → exact expected verdict + formatted `validUntil`.
41. Exit codes map correctly: 0/1/2/3 for PARKABLE/NOT_PARKABLE/NEEDS_REVIEW/DEPENDS.
42. `--extractor=stub` makes jar runnable offline.

**Total: ~42 concrete scenarios**, well past the 15–20 minimum. Bulk of test count lives in `TemporalRuleEvaluator` and `RulesEngine` (that's where the hard problems are).

---

## 9. Verification / Quality Assurance

### After Stage A (Domain Model + Temporal Engine)
- `mvn test` all green, ≥25 tests.
- Manually inspect a DST spring-forward and fall-back test: does it actually exercise the edge, or accidentally trivial?
- Manually inspect a midnight-crossing test: confirm the boolean logic in `TimeWindow.contains()` is exercised.

### After Stage B (Extraction + Validation)
- `mvn test` all green, ≥40 tests total.
- Manually run: `FixtureVisionExtractor` loads a sample fixture JSON, passes it through `ValidatingRetryingVisionExtractor`, confirm `RuleFactory` produces the expected `Rule` instances (golden test).

### After Stage C (CLI + Packaging)
- `mvn clean package` produces `target/parkable-cli.jar` with correct `Main-Class`.
- Run: `java -jar target/parkable-cli.jar src/test/resources/images/sign.jpg --now=2026-07-14T16:47:00Z --extractor=stub` and confirm it prints a verdict + trace + exits with correct code.
- `mvn test` all green, ≥50 tests.
- Architecture review: re-read the four rules in `CLAUDE.md` and confirm code satisfies each:
  1. Lambda handlers: zero business logic (N/A Phase 1, but confirm no business logic in CLI's `main()`).
  2. SignSource + VisionExtractor: interfaces from day one (confirm both exist as seams even if not fully exercised in Phase 1).
  3. Rules engine: evaluation instant as parameter (grep for `Instant.now()` outside `ScanCLI.main()` — should find zero).
  4. Every stored rule: source + version tag (confirm `ExtractionRecord` carries these even though Phase 1 doesn't persist to a real DB).

---

## 10. Critical Files Summary

**Will be created / modified during build:**

- `docs/schema.md` — JSON rule schema
- `backend/pom.xml` — Maven config
- `backend/src/main/java/com/parkable/model/` — domain classes
- `backend/src/main/java/com/parkable/calendar/` — holiday logic
- `backend/src/main/java/com/parkable/engine/` — rules engine (core)
- `backend/src/main/java/com/parkable/extraction/` — LLM extraction interface + impls
- `backend/src/main/java/com/parkable/validation/` — schema/semantic validators
- `backend/src/main/java/com/parkable/factory/` — rule factory
- `backend/src/main/java/com/parkable/repository/` — storage seam
- `backend/src/main/java/com/parkable/datasource/` — data source seam
- `backend/src/main/java/com/parkable/cli/` — CLI entry point
- `backend/src/main/resources/schema/parking-rule-schema.json` — JSON Schema
- `backend/src/test/java/...` — comprehensive test suite

---

## 11. Code Quality Mandate (Architectural Principles)

From `CLAUDE.md` + agreed user preferences:

- **SOLID throughout**: Single Responsibility (RulesEngine doesn't validate), Open/Closed (Decorator adds retry without modifying extractor), Liskov Substitution (all `VisionExtractor` impls are interchangeable), Interface Segregation (small focused interfaces), Dependency Inversion (depend on abstractions, not concrete classes).
- **Composition over inheritance**: `Rule` *has-a* `RuleMetadata`, not extends a base; `DayPattern` sealed with concrete impls.
- **Immutability**: Java 21 records, sealed classes, final fields; no mutable state in domain models.
- **Design patterns used deliberately**: Strategy (VisionExtractor), Builder (RuleBuilder), Factory (RuleFactory), Adapter (ClaudeVisionExtractor), Decorator (ValidatingRetryingVisionExtractor), Repository (RuleRepository).
- **Comment the WHY, not the WHAT**: Comments explain non-obvious temporal edge cases (midnight-crossing, DST, bounded scans), not `int day = 5; // day of week`.
- **TDD for temporal logic**: Write tests for edge cases (DST, midnight, holidays, nth-weekday, conflicts) before implementation.
- **No premature abstraction**: Only interfaces/patterns actually needed now; don't invent extension points nobody asked for.

---

## 12. Notes for Implementers

- Start with `docs/schema.md` — it's the contract. Everything else flows from the schema design.
- `TemporalRuleEvaluator` is the hardest component (DST, midnight-crossing, yesterday-tail spillover, bounded forward-scan). Invest time in tests and reasoning before code.
- `FixtureVisionExtractor` unblocks CLI/tests before real API is ready. Implement it early.
- Jackson configuration for `ExtractedSignData` DTOs: use `@JsonProperty` + `@JsonDeserialize` where schema field names differ from Java conventions.
- `networknt/json-schema-validator` reference: [https://github.com/networknt/json-schema-validator](https://github.com/networknt/json-schema-validator)
- All temporal operations use `java.time` API (no Joda-Time legacy). ZoneId defaults are testable (pass as parameters, not system state).

---

**End of Phase 1 Architecture Plan**
