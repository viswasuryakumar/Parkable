package com.parkable.cli;

import com.parkable.calendar.UsFederalHolidayCalendar;
import com.parkable.engine.RulesEngine;
import com.parkable.engine.TemporalRuleEvaluator;
import com.parkable.extraction.ClaudeVisionExtractor;
import com.parkable.extraction.ExtractionResult;
import com.parkable.extraction.FixtureVisionExtractor;
import com.parkable.extraction.ImageInput;
import com.parkable.extraction.ValidatingRetryingVisionExtractor;
import com.parkable.extraction.VisionExtractor;
import com.parkable.factory.RuleFactory;
import com.parkable.model.Rule;
import com.parkable.model.VerdictResult;
import com.parkable.validation.RuleJsonSchemaValidator;
import com.parkable.validation.SemanticRuleValidator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Local CLI: image in, verdict + trace out. Thin orchestration only — every
 * decision lives in the extraction/validation/engine components, so the CLI
 * (like a future Lambda handler) contains zero business logic.
 *
 * <p>Exit codes are distinct so the CLI is scriptable:
 * 0=PARKABLE, 1=NOT_PARKABLE, 2=NEEDS_REVIEW, 3=DEPENDS, 64=usage, 66=bad image.
 */
public final class ScanCLI {

    static final int EXIT_PARKABLE = 0;
    static final int EXIT_NOT_PARKABLE = 1;
    static final int EXIT_NEEDS_REVIEW = 2;
    static final int EXIT_DEPENDS = 3;
    static final int EXIT_USAGE = 64;
    static final int EXIT_BAD_IMAGE = 66;

    private ScanCLI() {}

    public static void main(String[] args) {
        // The only place wall-clock time enters the system.
        System.exit(run(args, Clock.systemUTC(), System.out, System.err));
    }

    static int run(String[] args, Clock clock, PrintStream out, PrintStream err) {
        CliArgs parsed;
        try {
            parsed = CliArgs.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            err.println();
            err.println(CliArgs.USAGE);
            return EXIT_USAGE;
        }

        byte[] imageBytes;
        try {
            imageBytes = Files.readAllBytes(parsed.imagePath());
        } catch (IOException e) {
            err.println("Error: cannot read image file " + parsed.imagePath() + " (" + e.getMessage() + ")");
            return EXIT_BAD_IMAGE;
        }
        ImageInput image = new ImageInput(imageBytes, mediaTypeFor(parsed.imagePath()), parsed.imagePath());

        VisionExtractor delegate = parsed.extractor().equals("claude")
                ? new ClaudeVisionExtractor()
                : new FixtureVisionExtractor();
        VisionExtractor extractor = new ValidatingRetryingVisionExtractor(
                delegate, new RuleJsonSchemaValidator(), new SemanticRuleValidator());

        ExtractionResult result = extractor.extract(image);
        if (result instanceof ExtractionResult.NeedsReview review) {
            // Honest uncertainty: no verdict without trustworthy rules.
            OutputFormatter.printNeedsReview(review, out);
            return EXIT_NEEDS_REVIEW;
        }

        ExtractionResult.Success success = (ExtractionResult.Success) result;
        List<Rule> rules = RuleFactory.fromEnvelope(success.envelope());

        RulesEngine engine = new RulesEngine(new TemporalRuleEvaluator(new UsFederalHolidayCalendar()));
        Instant now = parsed.now().orElseGet(clock::instant);
        VerdictResult verdict = engine.evaluate(rules, now, parsed.zone(), parsed.side());

        OutputFormatter.printVerdict(verdict, parsed.zone(), out);
        return switch (verdict.verdict()) {
            case PARKABLE -> EXIT_PARKABLE;
            case NOT_PARKABLE -> EXIT_NOT_PARKABLE;
            case DEPENDS -> EXIT_DEPENDS;
        };
    }

    private static String mediaTypeFor(Path imagePath) {
        String name = imagePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }
}
