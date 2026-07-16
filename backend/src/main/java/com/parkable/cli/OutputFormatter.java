package com.parkable.cli;

import com.parkable.extraction.ExtractionResult;
import com.parkable.model.VerdictResult;

import java.io.PrintStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Renders engine output for humans. Pure presentation — no business logic
 * lives here (the trace comes fully formed from the engine).
 */
final class OutputFormatter {

    private static final DateTimeFormatter LOCAL_TIME =
            DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm zzz");

    private OutputFormatter() {}

    static void printVerdict(VerdictResult result, ZoneId zone, PrintStream out) {
        out.println("Verdict: " + result.verdict());
        result.triggeringRule().ifPresent(match -> {
            out.println("Rule:    " + match.rule().metadata().ruleId());
            out.println("Reason:  " + match.reason());
        });
        out.println("Valid until: " + result.validUntil()
                .map(instant -> LOCAL_TIME.format(instant.atZone(zone)))
                .orElse("no known expiration"));
        out.println();
        out.println("Trace:");
        result.trace().forEach(step -> out.println("  - " + step));
    }

    static void printNeedsReview(ExtractionResult.NeedsReview review, PrintStream out) {
        out.println("Verdict: NEEDS_REVIEW");
        out.println(review.message());
        if (!review.details().isEmpty()) {
            out.println();
            out.println("Details (for debugging):");
            review.details().forEach(detail -> out.println("  - " + detail));
        }
    }
}
