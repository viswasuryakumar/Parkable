package com.parkable.cli;

import com.parkable.model.Side;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliArgsTest {

    @Test
    void defaultsAreOfflineSanFrancisco() {
        CliArgs args = CliArgs.parse(new String[] {"sign.jpg"});

        assertThat(args.imagePath()).isEqualTo(Path.of("sign.jpg"));
        assertThat(args.now()).isEmpty();
        assertThat(args.zone()).isEqualTo(ZoneId.of("America/Los_Angeles"));
        assertThat(args.side()).isEmpty();
        assertThat(args.extractor()).isEqualTo("stub");
    }

    @Test
    void parsesAllOptions() {
        CliArgs args = CliArgs.parse(new String[] {
                "sign.jpg",
                "--now=2026-07-14T18:04:00Z",
                "--zone=America/New_York",
                "--side=LEFT",
                "--extractor=claude",
        });

        assertThat(args.now()).contains(Instant.parse("2026-07-14T18:04:00Z"));
        assertThat(args.zone()).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(args.side()).contains(Side.LEFT);
        assertThat(args.extractor()).isEqualTo("claude");
    }

    @Test
    void rejectsBadInput() {
        assertThatThrownBy(() -> CliArgs.parse(new String[] {}))
                .hasMessageContaining("image path");
        assertThatThrownBy(() -> CliArgs.parse(new String[] {"a.jpg", "--now=yesterday"}))
                .hasMessageContaining("--now");
        assertThatThrownBy(() -> CliArgs.parse(new String[] {"a.jpg", "--side=UP"}))
                .hasMessageContaining("--side");
        assertThatThrownBy(() -> CliArgs.parse(new String[] {"a.jpg", "--extractor=gemini"}))
                .hasMessageContaining("--extractor");
        assertThatThrownBy(() -> CliArgs.parse(new String[] {"a.jpg", "--frobnicate"}))
                .hasMessageContaining("Unknown option");
        assertThatThrownBy(() -> CliArgs.parse(new String[] {"a.jpg", "b.jpg"}))
                .hasMessageContaining("extra argument");
    }
}
