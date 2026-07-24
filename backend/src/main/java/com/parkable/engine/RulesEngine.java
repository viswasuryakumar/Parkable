package com.parkable.engine;

import com.parkable.model.InformationalRule;
import com.parkable.model.NoParkingRule;
import com.parkable.model.PermitRule;
import com.parkable.model.Rule;
import com.parkable.model.RuleMatch;
import com.parkable.model.Side;
import com.parkable.model.TimeLimitRule;
import com.parkable.model.Verdict;
import com.parkable.model.VerdictResult;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Multi-rule aggregation: runs the {@link TemporalRuleEvaluator} per rule,
 * resolves side-of-street ambiguity, and applies most-restrictive-wins.
 *
 * <p>The evaluation instant is always a parameter — never {@code Instant.now()}
 * — so every verdict is reproducible.
 */
public final class RulesEngine {

    private record Evaluated(Rule rule, RuleActivation activation) {}

    private final TemporalRuleEvaluator evaluator;

    public RulesEngine(TemporalRuleEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    public VerdictResult evaluate(List<Rule> rules, Instant instant, ZoneId zone, Optional<Side> observerSide) {
        List<String> trace = new ArrayList<>();
        if (rules.isEmpty()) {
            trace.add("No rules to evaluate; defaulting to PARKABLE with no expiry.");
            return new VerdictResult(Verdict.PARKABLE, Optional.empty(), Optional.empty(), trace);
        }

        ZonedDateTime at = instant.atZone(zone);
        trace.add("Evaluating " + rules.size() + " rule(s) at " + at + ".");

        List<Evaluated> evaluations = rules.stream()
                .map(rule -> new Evaluated(rule, evaluator.evaluate(rule, at)))
                .toList();
        evaluations.forEach(e -> trace.add(
                "Rule " + e.rule().metadata().ruleId() + ": " + e.activation().reason()));

        // validUntil spans ALL rules, active or not: "when could the answer
        // change" includes an inactive rule about to activate.
        Optional<Instant> validUntil = evaluations.stream()
                .map(e -> e.activation().nextBoundary())
                .flatMap(Optional::stream)
                .min(Instant::compareTo);

        List<Rule> activeRules = evaluations.stream()
                .filter(e -> e.activation().active())
                .map(Evaluated::rule)
                .toList();

        if (observerSide.isPresent()) {
            Side side = observerSide.get();
            activeRules = activeRules.stream().filter(rule -> appliesToSide(rule, side)).toList();
            trace.add("Observer side " + side + ": " + activeRules.size() + " active rule(s) apply.");
        } else if (hasDirectionalConflict(activeRules)) {
            // DEPENDS only when it matters: opposite sides would produce
            // DIFFERENT verdicts and we don't know which side the car is on.
            trace.add("Directional conflict: LEFT and RIGHT yield different verdicts and no observer side given.");
            return new VerdictResult(Verdict.DEPENDS, Optional.empty(), validUntil, trace);
        }

        Optional<Rule> triggering = pickMostRestrictive(activeRules);
        Verdict verdict = triggering.map(RulesEngine::verdictOf).orElse(Verdict.PARKABLE);
        Optional<RuleMatch> match = triggering.map(rule -> new RuleMatch(rule, reasonFor(rule)));

        // Under an active time limit, "when could the answer change" for a
        // driver parking NOW is the earlier of the rule's own boundary and
        // the moment their allowance runs out - a 2h limit inside an
        // 8:30-17:30 window means "move by now+2h", not "move by 17:30".
        if (triggering.orElse(null) instanceof TimeLimitRule limit) {
            Instant limitExpiry = instant.plus(limit.limit());
            validUntil = Optional.of(validUntil.filter(boundary -> boundary.isBefore(limitExpiry))
                    .orElse(limitExpiry));
            trace.add("Active time limit: allowance from now expires at " + limitExpiry + ".");
        }

        trace.add("Verdict: " + verdict
                + match.map(m -> " — " + m.reason()).orElse(" — no active rules restrict parking."));
        return new VerdictResult(verdict, match, validUntil, trace);
    }

    private static boolean appliesToSide(Rule rule, Side observerSide) {
        Side side = rule.metadata().direction().side();
        return side == observerSide || side == Side.BOTH || side == Side.NOT_SPECIFIED;
    }

    private static boolean hasDirectionalConflict(List<Rule> activeRules) {
        boolean hasLeft = activeRules.stream().anyMatch(r -> r.metadata().direction().side() == Side.LEFT);
        boolean hasRight = activeRules.stream().anyMatch(r -> r.metadata().direction().side() == Side.RIGHT);
        if (!hasLeft || !hasRight) {
            return false;
        }
        // Two active rules on different sides that AGREE on the outcome do not
        // create real ambiguity; comparing per-side verdicts avoids
        // manufacturing a false DEPENDS.
        return verdictForSide(activeRules, Side.LEFT) != verdictForSide(activeRules, Side.RIGHT);
    }

    private static Verdict verdictForSide(List<Rule> activeRules, Side side) {
        List<Rule> applicable = activeRules.stream()
                .filter(rule -> appliesToSide(rule, side))
                .toList();
        return pickMostRestrictive(applicable).map(RulesEngine::verdictOf).orElse(Verdict.PARKABLE);
    }

    private static Optional<Rule> pickMostRestrictive(List<Rule> activeRules) {
        Rule best = null;
        for (Rule rule : activeRules) {
            // Strict comparison keeps the FIRST rule in list order on ties,
            // giving deterministic trace/triggering-rule output.
            if (best == null || restrictiveness(rule) > restrictiveness(best)) {
                best = rule;
            }
        }
        return Optional.ofNullable(best);
    }

    private static int restrictiveness(Rule rule) {
        return switch (rule) {
            case NoParkingRule ignored -> 3;
            case PermitRule ignored -> 2;
            case TimeLimitRule ignored -> 1;
            // Never restricts a single legally-occupied curb space (e.g. "No
            // Double Parking" only prohibits a second row) - weakest of all,
            // so any genuine restriction sharing the sign always outranks it.
            case InformationalRule ignored -> 0;
        };
    }

    private static Verdict verdictOf(Rule rule) {
        return switch (rule) {
            case NoParkingRule ignored -> Verdict.NOT_PARKABLE;
            // The engine cannot verify permit possession: honest answer is DEPENDS.
            case PermitRule ignored -> Verdict.DEPENDS;
            case TimeLimitRule ignored -> Verdict.PARKABLE;
            case InformationalRule ignored -> Verdict.PARKABLE;
        };
    }

    private static String reasonFor(Rule rule) {
        String description = rule.metadata().description();
        return switch (rule) {
            // Not "No parking in effect: <description>" - a live sign's own
            // description was "No loading" (a distinct concept from parking,
            // even though the engine's restrictiveness ranking treats it
            // like a no-parking rule), and stacking our own "No parking"
            // label in front of that read as contradictory nonsense to a
            // real user. The verdict headline above already says "Do not
            // park here"; this line's only job is explaining why.
            case NoParkingRule ignored -> "In effect: " + description;
            case PermitRule permit -> "Permit required (zone " + permit.permitZone() + "): " + description;
            case TimeLimitRule limit ->
                    "Time limit of " + limit.limit().toMinutes() + " minutes applies: " + description;
            // Never the reason parking is disallowed - if this is the
            // triggering rule, nothing else on the sign restricts the space.
            case InformationalRule ignored -> "Does not restrict this space: " + description;
        };
    }
}
