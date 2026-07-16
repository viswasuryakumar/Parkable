package com.parkable.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end CLI tests over the offline fixture extractor — the full
 * image → extraction → validation → factory → engine → formatted output path.
 */
class ScanCLITest {

    private static final String SIGN = "src/test/resources/fixtures/sign.jpg";
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-14T18:04:00Z"), ZoneOffset.UTC);

    private record CliRun(int exitCode, String stdout, String stderr) {}

    private static CliRun run(String... args) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exitCode = ScanCLI.run(args,
                FIXED_CLOCK,
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
        return new CliRun(exitCode,
                outBytes.toString(StandardCharsets.UTF_8),
                errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void insideNoParkingWindowExitsOneWithFullReport() {
        // Tuesday 11:04 PDT, inside the fixture's Mon-Fri 08:00-18:00 window.
        CliRun result = run(SIGN, "--now=2026-07-14T18:04:00Z", "--extractor=stub");

        assertThat(result.exitCode()).isEqualTo(ScanCLI.EXIT_NOT_PARKABLE);
        assertThat(result.stdout()).contains("Verdict: NOT_PARKABLE");
        assertThat(result.stdout()).contains("sign-rule-1");
        // Window end 18:00 PDT rendered in the default zone.
        assertThat(result.stdout()).contains("Valid until: Tue 2026-07-14 18:00");
        assertThat(result.stdout()).contains("Trace:");
    }

    @Test
    void outsideWindowExitsZero() {
        CliRun result = run(SIGN, "--now=2026-07-15T02:30:00Z", "--extractor=stub");

        assertThat(result.exitCode()).isEqualTo(ScanCLI.EXIT_PARKABLE);
        assertThat(result.stdout()).contains("Verdict: PARKABLE");
    }

    @Test
    void missingNowUsesInjectedClock() {
        // FIXED_CLOCK is inside the window, so the verdict matches --now above.
        CliRun result = run(SIGN, "--extractor=stub");

        assertThat(result.exitCode()).isEqualTo(ScanCLI.EXIT_NOT_PARKABLE);
    }

    @Test
    void permitFixtureExitsDepends() {
        CliRun result = run("src/test/resources/fixtures/permit.jpg",
                "--now=2026-07-14T18:04:00Z", "--extractor=stub");

        assertThat(result.exitCode()).isEqualTo(ScanCLI.EXIT_DEPENDS);
        assertThat(result.stdout()).contains("Verdict: DEPENDS");
        assertThat(result.stdout()).contains("Permit required");
    }

    @Test
    void invalidExtractionExitsNeedsReviewWithoutEngineRun() {
        CliRun result = run("src/test/resources/fixtures/invalid-missing-type.jpg",
                "--now=2026-07-14T18:04:00Z", "--extractor=stub");

        assertThat(result.exitCode()).isEqualTo(ScanCLI.EXIT_NEEDS_REVIEW);
        assertThat(result.stdout()).contains("NEEDS_REVIEW");
        assertThat(result.stdout()).contains("retake");
        assertThat(result.stdout()).doesNotContain("Verdict: PARKABLE");
    }

    @Test
    void badArgumentsExitUsageWithHelpText() {
        CliRun result = run("--side=UP");

        assertThat(result.exitCode()).isEqualTo(ScanCLI.EXIT_USAGE);
        assertThat(result.stderr()).contains("Usage: parkable-cli");
    }

    @Test
    void unreadableImageExitsBadImage() {
        CliRun result = run("no/such/image.jpg", "--extractor=stub");

        assertThat(result.exitCode()).isEqualTo(ScanCLI.EXIT_BAD_IMAGE);
        assertThat(result.stderr()).contains("cannot read image file");
    }
}
