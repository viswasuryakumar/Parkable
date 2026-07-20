package com.parkable.lambda.config;

import com.parkable.extraction.ClaudeVisionExtractor;
import com.parkable.extraction.FixtureVisionExtractor;
import com.parkable.extraction.VisionExtractor;

/**
 * Chooses the extraction provider: Claude when an API key is configured
 * (plan decision D4), the offline fixture otherwise — so local/dev runs
 * never require a key and never cost money.
 */
public final class ExtractorFactory {

    private ExtractorFactory() {}

    public static boolean useClaudeExtractor(EnvConfig config) {
        return config.anthropicApiKey().isPresent();
    }

    public static VisionExtractor extractor(EnvConfig config) {
        return useClaudeExtractor(config) ? new ClaudeVisionExtractor() : new FixtureVisionExtractor();
    }
}
