package com.parkable.datasource;

import com.parkable.extraction.VisionExtractor;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CameraScanSignSourceTest {

    @Test
    void implementsTheSignSourceSeamAgainstVisionExtraction() {
        VisionExtractor extractor = mock(VisionExtractor.class);

        SignSource source = new CameraScanSignSource(extractor);

        assertThat(source).isInstanceOf(CameraScanSignSource.class);
    }

    @Test
    void fetchIsExplicitlyUnsupportedUntilCameraScanningIsIntegrated() {
        VisionExtractor extractor = mock(VisionExtractor.class);
        SignSource source = new CameraScanSignSource(extractor);

        assertThatThrownBy(() -> source.fetch(37.7749, -122.4194, ZoneId.of("America/Los_Angeles")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Phase 1");
        verifyNoInteractions(extractor);
    }

    @Test
    void requiresAnExtractionStrategy() {
        assertThatThrownBy(() -> new CameraScanSignSource(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("extractor");
    }
}
