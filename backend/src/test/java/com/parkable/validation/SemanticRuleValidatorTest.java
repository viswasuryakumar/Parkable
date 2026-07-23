package com.parkable.validation;

import com.parkable.extraction.dto.DayPatternDto;
import com.parkable.extraction.dto.ExtractionEnvelope;
import com.parkable.extraction.dto.RestrictionDto;
import com.parkable.extraction.dto.RuleDto;
import com.parkable.extraction.dto.TimeWindowDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticRuleValidatorTest {

    private static final DayPatternDto ANY_DAY =
            new DayPatternDto("any_day", null, null, null, null, null, null);

    private final SemanticRuleValidator validator = new SemanticRuleValidator();

    private static ExtractionEnvelope envelopeWith(RuleDto... rules) {
        return new ExtractionEnvelope("e1", "camera_scan", null, null, "test-v1",
                "2026-07-14T18:04:00Z", null, 0.9, null, null, null, List.of(rules));
    }

    private static RuleDto rule(String id, TimeWindowDto window, DayPatternDto pattern) {
        return new RuleDto(id, null, "no_parking", "d", null, null,
                window == null ? null : List.of(window), pattern, null, null, null);
    }

    @Test
    void cleanEnvelopePasses() {
        ValidationResult result = validator.validate(envelopeWith(
                rule("r1", new TimeWindowDto("08:00", "18:00", false, false), ANY_DAY)));

        assertThat(result.valid()).as("errors: " + result.errors()).isTrue();
    }

    @Test
    void midnightCrossingWindowWithConsistentFlagPasses() {
        ValidationResult result = validator.validate(envelopeWith(
                rule("r1", new TimeWindowDto("23:00", "02:00", true, false), ANY_DAY)));

        assertThat(result.valid()).as("errors: " + result.errors()).isTrue();
    }

    @Test
    void duplicateRuleIdsRejected() {
        ValidationResult result = validator.validate(envelopeWith(
                rule("r1", null, ANY_DAY), rule("r1", null, ANY_DAY)));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("Duplicate rule_id"));
    }

    @Test
    void zeroDurationWindowRejected() {
        ValidationResult result = validator.validate(envelopeWith(
                rule("r1", new TimeWindowDto("08:00", "08:00", null, false), ANY_DAY)));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("start equals end"));
    }

    @Test
    void contradictoryCrossesMidnightFlagRejected() {
        // Flag says it crosses midnight but the times say otherwise, and vice versa.
        ValidationResult claimsCrossing = validator.validate(envelopeWith(
                rule("r1", new TimeWindowDto("08:00", "18:00", true, false), ANY_DAY)));
        ValidationResult deniesCrossing = validator.validate(envelopeWith(
                rule("r1", new TimeWindowDto("23:00", "02:00", false, false), ANY_DAY)));

        assertThat(claimsCrossing.valid()).isFalse();
        assertThat(deniesCrossing.valid()).isFalse();
    }

    @Test
    void unparseableTimestampRejected() {
        ExtractionEnvelope envelope = new ExtractionEnvelope("e1", "camera_scan", null, null,
                "test-v1", "yesterday-ish", null, 0.9, null, null, null,
                List.of(rule("r1", null, ANY_DAY)));

        ValidationResult result = validator.validate(envelope);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("ingestion_timestamp"));
    }

    @Test
    void sunsetBeforeEffectiveRejected() {
        ValidationResult result = validator.validate(envelopeWith(rule("r1", null,
                new DayPatternDto("date_range", null, null, null, "2026-08-31", "2026-06-01", null))));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("precedes effective_date"));
    }

    @Test
    void unparseableDateRejected() {
        ValidationResult result = validator.validate(envelopeWith(rule("r1", null,
                new DayPatternDto("date_range", null, null, null, "June 1st", null, null))));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("effective_date"));
    }

    @Test
    void greenCurbWithoutDurationRejected() {
        RuleDto greenCurb = new RuleDto("r1", null, "color_curb", "d", null,
                new RestrictionDto(null, null, "green", null, null), null, ANY_DAY, null, null, null);

        ValidationResult result = validator.validate(envelopeWith(greenCurb));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("green"));
    }

    @Test
    void lowConfidenceExtractionIsRejectedEvenWhenStructurallyValid() {
        // The exact real-world failure: a blurry photo still parses into
        // well-formed JSON, so structural validation alone would accept it.
        ExtractionEnvelope envelope = new ExtractionEnvelope("e1", "camera_scan", null, null,
                "test-v1", "2026-07-14T18:04:00Z", null, 0.4, null, null, null,
                List.of(rule("r1", new TimeWindowDto("08:00", "18:00", false, false), ANY_DAY)));

        ValidationResult result = validator.validate(envelope);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("confidence"));
    }

    @Test
    void confidenceAtOrAboveThresholdPasses() {
        ExtractionEnvelope envelope = new ExtractionEnvelope("e1", "camera_scan", null, null,
                "test-v1", "2026-07-14T18:04:00Z", null, 0.7, null, null, null,
                List.of(rule("r1", new TimeWindowDto("08:00", "18:00", false, false), ANY_DAY)));

        ValidationResult result = validator.validate(envelope);

        assertThat(result.valid()).as("errors: " + result.errors()).isTrue();
    }

    @Test
    void missingConfidenceIsNotPenalized() {
        // Gov ETL and other non-LLM sources may never set confidence at all;
        // absence isn't the same claim as "I'm unsure".
        ExtractionEnvelope envelope = new ExtractionEnvelope("e1", "camera_scan", null, null,
                "test-v1", "2026-07-14T18:04:00Z", null, null, null, null, null,
                List.of(rule("r1", new TimeWindowDto("08:00", "18:00", false, false), ANY_DAY)));

        ValidationResult result = validator.validate(envelope);

        assertThat(result.valid()).as("errors: " + result.errors()).isTrue();
    }

    @Test
    void emptyTimeWindowsWithClockTimeInDescriptionIsRejected() {
        RuleDto rule = new RuleDto("r1", null, "time_limit", "2 Hour Parking 8AM to 5PM", null,
                new RestrictionDto(120, null, null, null, null), null, ANY_DAY, null, null, null);
        ExtractionEnvelope envelope = new ExtractionEnvelope("e1", "camera_scan", null, null,
                "test-v1", "2026-07-14T18:04:00Z", null, 0.9, null, null, null, List.of(rule));

        ValidationResult result = validator.validate(envelope);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("time_windows is empty"));
    }

    @Test
    void emptyTimeWindowsWithClockTimeOnlyInRawTextIsRejected() {
        // The real bug this guards: description/type/duration all correctly
        // extracted, hours only appear in the sign's raw transcription, and
        // time_windows was silently left empty.
        RuleDto rule = new RuleDto("r1", null, "time_limit", "2 Hour Parking", null,
                new RestrictionDto(120, null, null, null, null), null, ANY_DAY, null, null, null);
        ExtractionEnvelope envelope = new ExtractionEnvelope("e1", "camera_scan", null, null,
                "test-v1", "2026-07-14T18:04:00Z", null, 0.9, null, null,
                "2 HOUR PARKING MONDAY THRU FRIDAY 8AM - 5PM", List.of(rule));

        ValidationResult result = validator.validate(envelope);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("time_windows is empty"));
    }

    @Test
    void emptyTimeWindowsWithNoClockTimeAnywhereIsALegitimateAnyTimeSign() {
        RuleDto rule = new RuleDto("r1", null, "no_parking", "No Parking Anytime", null,
                null, null, ANY_DAY, null, null, null);
        ExtractionEnvelope envelope = new ExtractionEnvelope("e1", "camera_scan", null, null,
                "test-v1", "2026-07-14T18:04:00Z", null, 0.9, null, null,
                "NO PARKING ANYTIME", List.of(rule));

        ValidationResult result = validator.validate(envelope);

        assertThat(result.valid()).as("errors: " + result.errors()).isTrue();
    }

    @Test
    void multiRuleSignsDoNotFalsePositiveAcrossRules() {
        // Rule 1 has real hours; rule 2 is legitimately any-time. The
        // envelope-level rawText check must not attribute rule 1's hours to
        // rule 2 just because they share one sign's transcription.
        RuleDto withHours = new RuleDto("r1", null, "time_limit", "2 Hour Parking 8AM-5PM", null,
                new RestrictionDto(120, null, null, null, null),
                List.of(new TimeWindowDto("08:00", "17:00", false, false)), ANY_DAY, null, null, null);
        RuleDto anyTime = new RuleDto("r2", null, "no_parking", "No Parking Anytime Sundays", null,
                null, null, new DayPatternDto("specific_days", List.of("SUN"), null, null, null, null, null),
                null, null, null);
        ExtractionEnvelope envelope = new ExtractionEnvelope("e1", "camera_scan", null, null,
                "test-v1", "2026-07-14T18:04:00Z", null, 0.9, null, null,
                "2 HOUR PARKING 8AM-5PM MON-FRI. NO PARKING ANYTIME SUNDAYS.", List.of(withHours, anyTime));

        ValidationResult result = validator.validate(envelope);

        assertThat(result.valid()).as("errors: " + result.errors()).isTrue();
    }

    @Test
    void allErrorsAccumulateInsteadOfStoppingAtFirst() {
        ValidationResult result = validator.validate(envelopeWith(
                rule("r1", new TimeWindowDto("08:00", "08:00", null, false), ANY_DAY),
                rule("r1", null, new DayPatternDto("date_range", null, null, null, "bad-date", null, null))));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(3); // zero-duration + duplicate id + bad date
    }
}
