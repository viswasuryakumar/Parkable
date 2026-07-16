package com.parkable.extraction;

/**
 * Transport/IO failure during extraction (network error, missing fixture
 * file). Unreadable or invalid sign content is NOT exceptional — that flows
 * through {@link ExtractionResult.NeedsReview}.
 */
public class VisionExtractionException extends RuntimeException {
    public VisionExtractionException(String message) {
        super(message);
    }

    public VisionExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
