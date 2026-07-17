package com.parkable.lambda;

import com.parkable.model.Side;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

/**
 * Shared request-parameter parsing for the handlers. Throws
 * {@link BadRequestException} with a user-readable message; handlers turn
 * that into a 400.
 */
final class QueryParams {

    static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Los_Angeles");

    private QueryParams() {}

    static class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }
    }

    static double requireDouble(Map<String, String> params, String name) {
        String raw = params.get(name);
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Missing required parameter: " + name);
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Parameter " + name + " must be a number, got: " + raw);
        }
    }

    static void requireValidCoordinates(double latitude, double longitude) {
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new BadRequestException("Coordinates out of range: lat=" + latitude + " lng=" + longitude);
        }
    }

    static Optional<Instant> optionalInstant(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(raw));
        } catch (Exception e) {
            throw new BadRequestException(name + " must be an ISO-8601 instant, got: " + raw);
        }
    }

    static ZoneId zoneOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(raw);
        } catch (Exception e) {
            throw new BadRequestException("zone must be a valid ZoneId, got: " + raw);
        }
    }

    static Optional<Side> optionalSide(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return switch (raw.toUpperCase()) {
            case "LEFT" -> Optional.of(Side.LEFT);
            case "RIGHT" -> Optional.of(Side.RIGHT);
            default -> throw new BadRequestException("side must be LEFT or RIGHT, got: " + raw);
        };
    }
}
