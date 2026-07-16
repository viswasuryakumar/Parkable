package com.parkable.extraction;

/**
 * Strategy seam for pluggable extraction providers. Implementations extract
 * parking rules from an image as structured JSON; they NEVER decide a verdict
 * (the engine's job). Adding a provider must not touch the engine.
 */
public interface VisionExtractor {

    /**
     * @return a {@link ExtractionResult.Success} with the parsed envelope, or
     *         {@link ExtractionResult.NeedsReview} when the sign couldn't be
     *         read into valid structure
     * @throws VisionExtractionException on genuinely exceptional failures
     *         (network/IO, missing fixture) — not on unreadable signs
     */
    ExtractionResult extract(ImageInput image);
}
