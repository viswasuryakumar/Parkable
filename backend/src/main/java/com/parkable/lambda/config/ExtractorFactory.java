package com.parkable.lambda.config;

import com.parkable.extraction.ClaudeVisionExtractor;
import com.parkable.extraction.FixtureVisionExtractor;
import com.parkable.extraction.OpenRouterVisionExtractor;
import com.parkable.extraction.VisionExtractor;

/**
 * Chooses the extraction provider, cheapest-first: OpenRouter when
 * configured (lets a cost-sensitive deployment point at whichever
 * vision-capable model is currently cheapest — see
 * {@link OpenRouterVisionExtractor}), Claude when only that key is present,
 * the offline fixture otherwise — so local/dev runs never require a key and
 * never cost money.
 */
public final class ExtractorFactory {

    private ExtractorFactory() {}

    public static boolean useOpenRouterExtractor(EnvConfig config) {
        return config.openRouterApiKey().isPresent();
    }

    public static boolean useClaudeExtractor(EnvConfig config) {
        return !useOpenRouterExtractor(config) && config.anthropicApiKey().isPresent();
    }

    public static VisionExtractor extractor(EnvConfig config) {
        if (useOpenRouterExtractor(config)) {
            return new OpenRouterVisionExtractor(config.openRouterApiKey().orElseThrow());
        }
        if (useClaudeExtractor(config)) {
            return new ClaudeVisionExtractor();
        }
        return new FixtureVisionExtractor();
    }
}
