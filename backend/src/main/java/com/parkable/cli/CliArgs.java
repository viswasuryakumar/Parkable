package com.parkable.cli;

import com.parkable.model.Side;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Parsed CLI arguments. Parsing is pure and throws
 * {@link IllegalArgumentException} with a user-readable message; the caller
 * decides how to render it (keeps this testable without capturing stderr).
 *
 * <p>{@code now} is empty when the user didn't pass {@code --now}; the CLI
 * fills it from its injected Clock — the ONE legitimate wall-clock read in
 * the codebase.
 */
public record CliArgs(
        Path imagePath,
        Optional<Instant> now,
        ZoneId zone,
        Optional<Side> side,
        String extractor
) {

    public static final String USAGE = """
            Usage: parkable-cli <image-path> [options]

            Options:
              --now=<ISO-8601 instant>   Evaluation time (default: current time)
              --zone=<ZoneId>            Time zone (default: America/Los_Angeles)
              --side=<LEFT|RIGHT>        Side of street the car is on
              --extractor=<stub|claude>  Extraction provider (default: stub, runs offline;
                                         claude requires ANTHROPIC_API_KEY)

            Exit codes: 0=PARKABLE  1=NOT_PARKABLE  2=NEEDS_REVIEW  3=DEPENDS
                        64=usage error  66=image not readable
            """;

    public static CliArgs parse(String[] args) {
        Path imagePath = null;
        Optional<Instant> now = Optional.empty();
        ZoneId zone = ZoneId.of("America/Los_Angeles"); // SF project focus
        Optional<Side> side = Optional.empty();
        String extractor = "stub";

        for (String arg : args) {
            if (arg.startsWith("--now=")) {
                now = Optional.of(parseInstant(value(arg)));
            } else if (arg.startsWith("--zone=")) {
                zone = parseZone(value(arg));
            } else if (arg.startsWith("--side=")) {
                side = Optional.of(parseSide(value(arg)));
            } else if (arg.startsWith("--extractor=")) {
                extractor = parseExtractor(value(arg));
            } else if (arg.startsWith("--")) {
                throw new IllegalArgumentException("Unknown option: " + arg);
            } else if (imagePath == null) {
                imagePath = Path.of(arg);
            } else {
                throw new IllegalArgumentException("Unexpected extra argument: " + arg);
            }
        }

        if (imagePath == null) {
            throw new IllegalArgumentException("Missing required image path argument");
        }
        return new CliArgs(imagePath, now, zone, side, extractor);
    }

    private static String value(String arg) {
        return arg.substring(arg.indexOf('=') + 1);
    }

    private static Instant parseInstant(String raw) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "--now must be an ISO-8601 instant like 2026-07-14T18:04:00Z, got: " + raw);
        }
    }

    private static ZoneId parseZone(String raw) {
        try {
            return ZoneId.of(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "--zone must be a valid ZoneId like America/Los_Angeles, got: " + raw);
        }
    }

    private static Side parseSide(String raw) {
        return switch (raw.toUpperCase()) {
            case "LEFT" -> Side.LEFT;
            case "RIGHT" -> Side.RIGHT;
            default -> throw new IllegalArgumentException("--side must be LEFT or RIGHT, got: " + raw);
        };
    }

    private static String parseExtractor(String raw) {
        if (!raw.equals("stub") && !raw.equals("claude")) {
            throw new IllegalArgumentException("--extractor must be stub or claude, got: " + raw);
        }
        return raw;
    }
}
