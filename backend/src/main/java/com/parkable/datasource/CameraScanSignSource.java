package com.parkable.datasource;

import com.parkable.extraction.VisionExtractor;
import com.parkable.model.Rule;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Adapter boundary between a camera-backed extractor and location-based sign
 * lookup. Phase 1 invokes extraction directly, so no photo belongs here yet.
 */
public final class CameraScanSignSource implements SignSource {

    private final VisionExtractor extractor;

    public CameraScanSignSource(VisionExtractor extractor) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    public List<Rule> fetch(double latitude, double longitude, ZoneId zone) {
        // Fetch has no image input in this Phase 1 interface. Failing loudly
        // prevents a caller from mistaking an unimplemented source for no rules.
        throw new UnsupportedOperationException("Phase 1: camera scanning not yet integrated");
    }
}
