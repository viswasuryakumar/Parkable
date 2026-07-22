package com.parkable.validation;

import com.parkable.extraction.dto.DayPatternDto;
import com.parkable.extraction.dto.ExtractionEnvelope;
import com.parkable.extraction.dto.RuleDto;
import com.parkable.extraction.dto.TimeWindowDto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cross-field validation the JSON Schema can't express (or doesn't assert by
 * default, like date parseability). Runs AFTER schema validation, so single
 * required fields and enum values can be assumed present/legal; everything
 * here is about combinations.
 *
 * <p>Never throws on invalid data — failures accumulate into the
 * {@link ValidationResult} and flow into the extraction retry path.
 */
public final class SemanticRuleValidator {

    // Matches "8AM", "8:30 PM", etc. A live scan produced description="2 Hour
    // Parking" (no hours) with raw_text="...8AM - 5PM" and an EMPTY
    // time_windows - which per schema.md means "enforced at all hours",
    // silently contradicting the sign. Empty time_windows is a much stronger
    // claim than "I didn't transcribe the hours", so it needs a positive
    // signal (no clock time anywhere) before it's trusted.
    private static final Pattern CLOCK_TIME = Pattern.compile("(?i)\\b\\d{1,2}(:\\d{2})?\\s*(AM|PM)\\b");

    public ValidationResult validate(ExtractionEnvelope envelope) {
        List<String> errors = new ArrayList<>();

        checkTimestamp(envelope.ingestionTimestamp(), errors);
        checkRuleIdUniqueness(envelope.rules(), errors);
        if (envelope.rules() != null) {
            for (RuleDto rule : envelope.rules()) {
                checkRule(rule, errors);
                // envelope.rawText() covers the WHOLE sign, so attributing it
                // to one specific rule only makes sense when there is
                // exactly one rule - with multiple regulations on a sign, an
                // hour mentioned for rule A must not false-flag rule B's
                // legitimately-empty time_windows.
                if (envelope.rules().size() == 1) {
                    checkHoursNotDropped(rule, envelope.rawText(), errors);
                }
            }
        }

        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.failure(errors);
    }

    private static void checkHoursNotDropped(RuleDto rule, String rawText, List<String> errors) {
        boolean hasWindows = rule.timeWindows() != null && !rule.timeWindows().isEmpty();
        if (hasWindows) {
            return;
        }
        String id = rule.ruleId() == null ? "<missing id>" : rule.ruleId();
        boolean mentionsClockTime = CLOCK_TIME.matcher(nullToEmpty(rule.description())).find()
                || CLOCK_TIME.matcher(nullToEmpty(rule.originalDescription())).find()
                || CLOCK_TIME.matcher(nullToEmpty(rawText)).find();
        if (mentionsClockTime) {
            errors.add("Rule " + id + ": sign text names specific hours but time_windows is empty "
                    + "(empty time_windows means enforced at ALL hours - re-extract the actual hours)");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void checkTimestamp(String timestamp, List<String> errors) {
        // Schema declares format:date-time but networknt doesn't assert
        // formats by default, so parseability is enforced here.
        if (timestamp == null) {
            return; // schema already reports the missing required field
        }
        try {
            Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            errors.add("ingestion_timestamp is not a valid ISO-8601 instant: " + timestamp);
        }
    }

    private static void checkRuleIdUniqueness(List<RuleDto> rules, List<String> errors) {
        if (rules == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (RuleDto rule : rules) {
            if (rule.ruleId() != null && !seen.add(rule.ruleId())) {
                errors.add("Duplicate rule_id within extraction: " + rule.ruleId());
            }
        }
    }

    private static void checkRule(RuleDto rule, List<String> errors) {
        String id = rule.ruleId() == null ? "<missing id>" : rule.ruleId();

        if (rule.timeWindows() != null) {
            for (TimeWindowDto window : rule.timeWindows()) {
                checkTimeWindow(id, window, errors);
            }
        }
        if (rule.dayPattern() != null) {
            checkDayPattern(id, rule.dayPattern(), errors);
        }

        // Factory maps a green curb to a time limit, which needs a duration;
        // any other color has an unconditional meaning.
        boolean greenCurb = "color_curb".equals(rule.type())
                && rule.restriction() != null
                && "green".equals(rule.restriction().color());
        if (greenCurb && rule.restriction().durationMinutes() == null) {
            errors.add("Rule " + id + ": green color_curb requires restriction.duration_minutes");
        }
    }

    private static void checkTimeWindow(String ruleId, TimeWindowDto window, List<String> errors) {
        if (Boolean.TRUE.equals(window.allDay())) {
            return; // start/end are ignored for all-day windows
        }
        LocalTime start = parseTime(window.startTime());
        LocalTime end = parseTime(window.endTime());
        if (start == null || end == null) {
            return; // schema's HH:mm pattern already reports these
        }
        if (start.equals(end)) {
            // Could mean "never" or "24h" — refuse to guess.
            errors.add("Rule " + ruleId + ": time window start equals end (" + start + "), ambiguous");
            return;
        }
        // crosses_midnight is redundant with start/end ordering; if the
        // extractor supplies it, it must agree — disagreement signals the LLM
        // misread the sign.
        if (window.crossesMidnight() != null && window.crossesMidnight() != end.isBefore(start)) {
            errors.add("Rule " + ruleId + ": crosses_midnight=" + window.crossesMidnight()
                    + " contradicts start=" + start + " end=" + end);
        }
    }

    private static void checkDayPattern(String ruleId, DayPatternDto pattern, List<String> errors) {
        LocalDate effective = parseDate(ruleId, "effective_date", pattern.effectiveDate(), errors);
        LocalDate sunset = parseDate(ruleId, "sunset_date", pattern.sunsetDate(), errors);
        if (effective != null && sunset != null && sunset.isBefore(effective)) {
            errors.add("Rule " + ruleId + ": sunset_date " + sunset
                    + " precedes effective_date " + effective);
        }
    }

    private static LocalTime parseTime(String time) {
        if (time == null) {
            return null;
        }
        try {
            return LocalTime.parse(time);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String ruleId, String field, String date, List<String> errors) {
        if (date == null) {
            return null;
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            // Schema declares format:date but doesn't assert it by default.
            errors.add("Rule " + ruleId + ": " + field + " is not a valid ISO-8601 date: " + date);
            return null;
        }
    }
}
