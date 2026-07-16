package com.parkable.extraction;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A photo handed to a {@link VisionExtractor}. {@code sourceReference} is a
 * local file path in Phase 1 (an S3 key in Phase 2) and doubles as the
 * reproducibility pointer stored with every extraction.
 */
public record ImageInput(byte[] bytes, String mediaType, Path sourceReference) {
    public ImageInput {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(sourceReference, "sourceReference");
    }
}
