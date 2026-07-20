package com.parkable.lambda.config;

import com.parkable.extraction.FixtureVisionExtractor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractorFactoryTest {

    @Test
    void noApiKeyMeansFixtureExtractor() {
        EnvConfig config = EnvConfig.from(Map.of());

        assertThat(ExtractorFactory.useClaudeExtractor(config)).isFalse();
        assertThat(ExtractorFactory.extractor(config)).isInstanceOf(FixtureVisionExtractor.class);
    }

    @Test
    void apiKeyPresentSelectsClaude() {
        EnvConfig config = EnvConfig.from(Map.of("ANTHROPIC_API_KEY", "sk-ant-test"));

        assertThat(ExtractorFactory.useClaudeExtractor(config)).isTrue();
    }
}
