package com.parkable.extraction;

import com.parkable.calendar.UsFederalHolidayCalendar;
import com.parkable.engine.RulesEngine;
import com.parkable.engine.TemporalRuleEvaluator;
import com.parkable.factory.RuleFactory;
import com.parkable.model.NoParkingRule;
import com.parkable.model.Rule;
import com.parkable.model.Verdict;
import com.parkable.model.VerdictResult;
import com.parkable.validation.RuleJsonSchemaValidator;
import com.parkable.validation.SemanticRuleValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden end-to-end test for the full Phase 1 pipeline: fixture image →
 * extraction → validation → RuleFactory → RulesEngine — exactly what the
 * Stage C CLI will orchestrate.
 */
class ExtractionPipelineTest {

    private static final Path SIGN_IMAGE = Path.of("src/test/resources/fixtures/sign.jpg");
    private static final ZoneId SAN_FRANCISCO = ZoneId.of("America/Los_Angeles");

    private final VisionExtractor extractor = new ValidatingRetryingVisionExtractor(
            new FixtureVisionExtractor(), new RuleJsonSchemaValidator(), new SemanticRuleValidator());
    private final RulesEngine engine = new RulesEngine(
            new TemporalRuleEvaluator(new UsFederalHolidayCalendar()));

    private List<Rule> extractRules() throws IOException {
        ImageInput image = new ImageInput(Files.readAllBytes(SIGN_IMAGE), "image/jpeg", SIGN_IMAGE);
        ExtractionResult result = extractor.extract(image);
        assertThat(result).isInstanceOf(ExtractionResult.Success.class);
        return RuleFactory.fromEnvelope(((ExtractionResult.Success) result).envelope());
    }

    @Test
    void weekdayInsideWindowIsNotParkableUntilWindowEnd() throws IOException {
        // Tuesday 2026-07-14 11:04 PDT — inside the Mon-Fri 08:00-18:00 window.
        VerdictResult verdict = engine.evaluate(extractRules(),
                Instant.parse("2026-07-14T18:04:00Z"), SAN_FRANCISCO, Optional.empty());

        assertThat(verdict.verdict()).isEqualTo(Verdict.NOT_PARKABLE);
        assertThat(verdict.triggeringRule()).isPresent();
        assertThat(verdict.triggeringRule().get().rule()).isInstanceOf(NoParkingRule.class);
        // Window ends 18:00 PDT = 2026-07-15T01:00:00Z.
        assertThat(verdict.validUntil()).contains(Instant.parse("2026-07-15T01:00:00Z"));
        assertThat(verdict.trace()).isNotEmpty();
    }

    @Test
    void eveningAfterWindowIsParkable() throws IOException {
        // Tuesday 2026-07-14 19:30 PDT — after the window closes.
        VerdictResult verdict = engine.evaluate(extractRules(),
                Instant.parse("2026-07-15T02:30:00Z"), SAN_FRANCISCO, Optional.empty());

        assertThat(verdict.verdict()).isEqualTo(Verdict.PARKABLE);
    }

    @Test
    void independenceDayObservedSuspendsTheRule() throws IOException {
        // 2026-07-04 falls on a Saturday, so the federal holiday is observed
        // Friday 2026-07-03 — a weekday at 10:00 PDT would normally be
        // NOT_PARKABLE, but the sign says "except holidays".
        VerdictResult verdict = engine.evaluate(extractRules(),
                Instant.parse("2026-07-03T17:00:00Z"), SAN_FRANCISCO, Optional.empty());

        assertThat(verdict.verdict()).isEqualTo(Verdict.PARKABLE);
    }
}
