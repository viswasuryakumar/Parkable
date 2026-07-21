package com.parkable.lambda.config;

import com.parkable.extraction.ClaudeVisionExtractor;
import com.parkable.extraction.FixtureVisionExtractor;
import com.parkable.extraction.OpenRouterVisionExtractor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractorFactoryTest {

    @Test
    void noKeysMeansFixtureExtractor() {
        EnvConfig config = EnvConfig.from(Map.of());

        assertThat(ExtractorFactory.useOpenRouterExtractor(config)).isFalse();
        assertThat(ExtractorFactory.useClaudeExtractor(config)).isFalse();
        assertThat(ExtractorFactory.extractor(config)).isInstanceOf(FixtureVisionExtractor.class);
    }

    @Test
    void anthropicKeyAloneSelectsClaude() {
        EnvConfig config = EnvConfig.from(Map.of("ANTHROPIC_API_KEY", "sk-ant-test"));

        assertThat(ExtractorFactory.useClaudeExtractor(config)).isTrue();
        assertThat(ExtractorFactory.extractor(config)).isInstanceOf(ClaudeVisionExtractor.class);
    }

    @Test
    void openRouterKeyAloneSelectsOpenRouter() {
        EnvConfig config = EnvConfig.from(Map.of("OPENROUTER_API_KEY", "sk-or-test"));

        assertThat(ExtractorFactory.useOpenRouterExtractor(config)).isTrue();
        assertThat(ExtractorFactory.extractor(config)).isInstanceOf(OpenRouterVisionExtractor.class);
    }

    @Test
    void openRouterTakesPriorityWhenBothKeysArePresent() {
        EnvConfig config = EnvConfig.from(Map.of(
                "OPENROUTER_API_KEY", "sk-or-test", "ANTHROPIC_API_KEY", "sk-ant-test"));

        assertThat(ExtractorFactory.useOpenRouterExtractor(config)).isTrue();
        assertThat(ExtractorFactory.useClaudeExtractor(config)).isFalse();
        assertThat(ExtractorFactory.extractor(config)).isInstanceOf(OpenRouterVisionExtractor.class);
    }
}
