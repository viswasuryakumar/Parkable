package com.parkable.validation;

import java.util.List;

/**
 * Outcome of a validation pass. Validators accumulate ALL errors rather than
 * stopping at the first, so retry prompts / logs show the complete picture.
 */
public record ValidationResult(boolean valid, List<String> errors) {

    public ValidationResult {
        errors = List.copyOf(errors);
        if (valid && !errors.isEmpty()) {
            throw new IllegalArgumentException("valid result must not carry errors: " + errors);
        }
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult failure(List<String> errors) {
        if (errors.isEmpty()) {
            throw new IllegalArgumentException("failure requires at least one error");
        }
        return new ValidationResult(false, errors);
    }
}
