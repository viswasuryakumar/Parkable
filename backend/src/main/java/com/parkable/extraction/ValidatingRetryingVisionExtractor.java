package com.parkable.extraction;

import com.parkable.validation.RuleJsonSchemaValidator;
import com.parkable.validation.SemanticRuleValidator;
import com.parkable.validation.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Decorator adding validate-then-retry-once semantics over any
 * {@link VisionExtractor}. Adding a new provider needs zero changes here
 * (Open/Closed); validation lives in this decorator, not in the extractors.
 *
 * <p>The user-facing NeedsReview message stays generic and honest; the
 * per-attempt validation errors go into {@code details} for trace/logs only.
 */
public final class ValidatingRetryingVisionExtractor implements VisionExtractor {

    static final String RETAKE_MESSAGE =
            "Couldn't read this sign clearly. Please retake with the full sign visible and in focus.";

    private static final int MAX_ATTEMPTS = 2;

    private final VisionExtractor delegate;
    private final RuleJsonSchemaValidator schemaValidator;
    private final SemanticRuleValidator semanticValidator;

    public ValidatingRetryingVisionExtractor(
            VisionExtractor delegate,
            RuleJsonSchemaValidator schemaValidator,
            SemanticRuleValidator semanticValidator) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator");
        this.semanticValidator = Objects.requireNonNull(semanticValidator, "semanticValidator");
    }

    @Override
    public ExtractionResult extract(ImageInput image) {
        List<String> details = new ArrayList<>();
        ExtractionResult lastResult = null;

        for (int i = 1; i <= MAX_ATTEMPTS; i++) {
            int attempt = i;
            ExtractionResult result = delegate.extract(image);
            lastResult = result;

            if (result instanceof ExtractionResult.NeedsReview needsReview) {
                needsReview.details().forEach(d -> details.add(prefix(attempt) + d));
                if (needsReview.details().isEmpty()) {
                    details.add(prefix(attempt) + needsReview.message());
                }
                continue;
            }

            ExtractionResult.Success success = (ExtractionResult.Success) result;
            // Structural first (on the raw tree), semantic second (on the
            // DTO): semantic checks may assume schema-guaranteed shape.
            ValidationResult validation = schemaValidator.validate(success.rawJson());
            if (validation.valid()) {
                validation = semanticValidator.validate(success.envelope());
            }
            if (validation.valid()) {
                return success;
            }
            validation.errors().forEach(e -> details.add(prefix(attempt) + e));
        }

        return new ExtractionResult.NeedsReview(RETAKE_MESSAGE, details, lastResult.metadata());
    }

    private static String prefix(int attempt) {
        return "attempt " + attempt + ": ";
    }
}
