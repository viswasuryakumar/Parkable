package com.parkable.engine;

import com.parkable.builder.RuleBuilder;
import com.parkable.model.Rule;
import com.parkable.model.Side;
import com.parkable.model.Verdict;
import com.parkable.model.VerdictResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static java.time.DayOfWeek.*;
import static org.assertj.core.api.Assertions.assertThat;

class RulesEngineTest {

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    /** Wednesday 2026-07-15 10:00 PDT. */
    private static final Instant WED_10AM = Instant.parse("2026-07-15T17:00:00Z");

    private final RulesEngine engine = new RulesEngine(new TemporalRuleEvaluator(date -> false));

    private static Rule noParkingBusinessHours(String id) {
        return new RuleBuilder().noParking().withId(id)
                .withDescription("No Parking Mon-Fri 8am-6pm")
                .onDays(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
                .duringWindow(LocalTime.of(8, 0), LocalTime.of(18, 0)).build();
    }

    private static Rule twoHourLimit(String id) {
        return new RuleBuilder().timeLimit(Duration.ofMinutes(120)).withId(id)
                .withDescription("2 Hour Parking Mon-Fri 8am-6pm")
                .onDays(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
                .duringWindow(LocalTime.of(8, 0), LocalTime.of(18, 0)).build();
    }

    @Test
    void emptyRuleListIsParkableWithNoBoundaryAndExplainingTrace() {
        VerdictResult result = engine.evaluate(List.of(), WED_10AM, LA, Optional.empty());
        assertThat(result.verdict()).isEqualTo(Verdict.PARKABLE);
        assertThat(result.triggeringRule()).isEmpty();
        assertThat(result.validUntil()).isEmpty();
        assertThat(String.join(" ", result.trace())).containsIgnoringCase("no rules");
    }

    @Test
    void singleActiveNoParkingRuleIsNotParkable() {
        VerdictResult result = engine.evaluate(
                List.of(noParkingBusinessHours("np-1")), WED_10AM, LA, Optional.empty());
        assertThat(result.verdict()).isEqualTo(Verdict.NOT_PARKABLE);
        assertThat(result.triggeringRule()).isPresent();
        assertThat(result.triggeringRule().orElseThrow().rule().metadata().ruleId()).isEqualTo("np-1");
    }

    @Test
    void singleActiveTimeLimitIsParkableWithLimitNoted() {
        VerdictResult result = engine.evaluate(
                List.of(twoHourLimit("tl-1")), WED_10AM, LA, Optional.empty());
        assertThat(result.verdict()).isEqualTo(Verdict.PARKABLE);
        assertThat(result.triggeringRule()).isPresent();
        assertThat(result.triggeringRule().orElseThrow().reason()).contains("120");
        assertThat(String.join(" ", result.trace())).contains("120");
    }

    @Test
    void noActiveRulesIsParkableWithSoonestBoundaryAsValidUntil() {
        Rule evening = new RuleBuilder().noParking().withId("np-eve").onAnyDay()
                .duringWindow(LocalTime.of(18, 0), LocalTime.of(20, 0)).build();
        VerdictResult result = engine.evaluate(List.of(evening), WED_10AM, LA, Optional.empty());
        assertThat(result.verdict()).isEqualTo(Verdict.PARKABLE);
        assertThat(result.triggeringRule()).isEmpty();
        assertThat(result.validUntil()).contains(Instant.parse("2026-07-16T01:00:00Z")); // 18:00 PDT
    }

    @Test
    void inactiveNoParkingWithActiveTimeLimitIsParkable() {
        Rule weekendNoParking = new RuleBuilder().noParking().withId("np-weekend")
                .onDays(SATURDAY, SUNDAY).anyTime().build();
        VerdictResult result = engine.evaluate(
                List.of(weekendNoParking, twoHourLimit("tl-1")), WED_10AM, LA, Optional.empty());
        assertThat(result.verdict()).isEqualTo(Verdict.PARKABLE);
        assertThat(result.triggeringRule().orElseThrow().rule().metadata().ruleId()).isEqualTo("tl-1");
    }

    @Test
    void simultaneousNoParkingAndTimeLimitMostRestrictiveWins() {
        VerdictResult result = engine.evaluate(
                List.of(twoHourLimit("tl-1"), noParkingBusinessHours("np-1")),
                WED_10AM, LA, Optional.empty());
        assertThat(result.verdict()).isEqualTo(Verdict.NOT_PARKABLE);
        assertThat(result.triggeringRule().orElseThrow().rule().metadata().ruleId()).isEqualTo("np-1");
        String trace = String.join(" ", result.trace());
        assertThat(trace).contains("tl-1").contains("np-1");
    }

    @Test
    void activePermitRuleYieldsDepends() {
        Rule permit = new RuleBuilder().permit("A").withId("permit-A")
                .withDescription("Residential Permit Zone A").onAnyDay().anyTime().build();
        VerdictResult result = engine.evaluate(List.of(permit), WED_10AM, LA, Optional.empty());
        assertThat(result.verdict()).isEqualTo(Verdict.DEPENDS);
        assertThat(result.triggeringRule().orElseThrow().reason()).containsIgnoringCase("permit");
    }

    @Test
    void directionalConflictWithoutObserverSideIsDepends() {
        Rule leftForbids = new RuleBuilder().noParking().withId("np-left")
                .onAnyDay().anyTime().onSide(Side.LEFT).build();
        Rule rightLimits = new RuleBuilder().timeLimit(Duration.ofHours(1)).withId("tl-right")
                .onAnyDay().anyTime().onSide(Side.RIGHT).build();
        VerdictResult result = engine.evaluate(
                List.of(leftForbids, rightLimits), WED_10AM, LA, Optional.empty());
        assertThat(result.verdict()).isEqualTo(Verdict.DEPENDS);
    }

    @Test
    void observerSideResolvesDirectionalConflict() {
        Rule leftForbids = new RuleBuilder().noParking().withId("np-left")
                .onAnyDay().anyTime().onSide(Side.LEFT).build();
        Rule rightLimits = new RuleBuilder().timeLimit(Duration.ofHours(1)).withId("tl-right")
                .onAnyDay().anyTime().onSide(Side.RIGHT).build();
        List<Rule> rules = List.of(leftForbids, rightLimits);

        VerdictResult right = engine.evaluate(rules, WED_10AM, LA, Optional.of(Side.RIGHT));
        assertThat(right.verdict()).isEqualTo(Verdict.PARKABLE);
        assertThat(right.triggeringRule().orElseThrow().rule().metadata().ruleId()).isEqualTo("tl-right");

        VerdictResult left = engine.evaluate(rules, WED_10AM, LA, Optional.of(Side.LEFT));
        assertThat(left.verdict()).isEqualTo(Verdict.NOT_PARKABLE);
        assertThat(left.triggeringRule().orElseThrow().rule().metadata().ruleId()).isEqualTo("np-left");
    }

    @Test
    void agreeingSidesDoNotManufactureDepends() {
        Rule leftForbids = new RuleBuilder().noParking().withId("np-left")
                .onAnyDay().anyTime().onSide(Side.LEFT).build();
        Rule rightForbids = new RuleBuilder().noParking().withId("np-right")
                .onAnyDay().anyTime().onSide(Side.RIGHT).build();
        VerdictResult result = engine.evaluate(
                List.of(leftForbids, rightForbids), WED_10AM, LA, Optional.empty());
        assertThat(result.verdict()).isEqualTo(Verdict.NOT_PARKABLE);
    }

    @Test
    void sideNeutralRuleStillAppliesWhenObserverSideGiven() {
        Rule bothSides = new RuleBuilder().noParking().withId("np-both")
                .onAnyDay().anyTime().onSide(Side.BOTH).build();
        VerdictResult result = engine.evaluate(
                List.of(bothSides), WED_10AM, LA, Optional.of(Side.RIGHT));
        assertThat(result.verdict()).isEqualTo(Verdict.NOT_PARKABLE);
    }

    @Test
    void multipleActivePermitRulesDependsPicksFirstInListOrder() {
        Rule permitA = new RuleBuilder().permit("A").withId("permit-A").onAnyDay().anyTime().build();
        Rule permitB = new RuleBuilder().permit("B").withId("permit-B").onAnyDay().anyTime().build();
        VerdictResult result = engine.evaluate(
                List.of(permitA, permitB), WED_10AM, LA, Optional.empty());
        assertThat(result.verdict()).isEqualTo(Verdict.DEPENDS);
        assertThat(result.triggeringRule().orElseThrow().rule().metadata().ruleId()).isEqualTo("permit-A");
    }

    @Test
    void validUntilConsidersInactiveRulesAboutToActivate() {
        // Active until 18:00, but an inactive rule kicks in at noon: the answer
        // could change then, so validUntil must be the earlier boundary.
        Rule active = noParkingBusinessHours("np-1");
        Rule lunchRule = new RuleBuilder().noParking().withId("np-lunch").onAnyDay()
                .duringWindow(LocalTime.of(12, 0), LocalTime.of(14, 0)).build();
        VerdictResult result = engine.evaluate(
                List.of(active, lunchRule), WED_10AM, LA, Optional.empty());
        assertThat(result.verdict()).isEqualTo(Verdict.NOT_PARKABLE);
        assertThat(result.validUntil()).contains(Instant.parse("2026-07-15T19:00:00Z")); // 12:00 PDT
    }
}
