package com.parkable.extraction;

import java.time.Instant;
import java.util.Objects;

/**
 * Provenance carried by every extraction outcome — the reproducibility
 * contract: photo + parser version + timestamp means old scans can be
 * reprocessed when extraction improves.
 */
public record ExtractionMetadata(String photoReference, String parserVersion, Instant extractedAt) {
    public ExtractionMetadata {
        Objects.requireNonNull(photoReference, "photoReference");
        Objects.requireNonNull(parserVersion, "parserVersion");
        Objects.requireNonNull(extractedAt, "extractedAt");
    }
}
