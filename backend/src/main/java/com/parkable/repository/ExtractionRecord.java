package com.parkable.repository;

import com.parkable.extraction.dto.ExtractionEnvelope;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistable extraction envelope and its provenance. Keeping the source and
 * parser version alongside the payload makes later reprocessing auditable.
 */
public record ExtractionRecord(
        ExtractionEnvelope envelope,
        String photoReference,
        String source,
        String parserVersion,
        Optional<GpsCoordinates> gpsLocation,
        Instant extractedAt) {

    public ExtractionRecord {
        Objects.requireNonNull(envelope, "envelope");
        photoReference = requireNonBlank(photoReference, "photoReference");
        source = requireNonBlank(source, "source");
        parserVersion = requireNonBlank(parserVersion, "parserVersion");
        gpsLocation = Objects.requireNonNull(gpsLocation, "gpsLocation");
        Objects.requireNonNull(extractedAt, "extractedAt");
        requireNonBlank(envelope.extractionId(), "envelope.extractionId");
    }

    public String extractionId() {
        return envelope.extractionId();
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Geographic coordinates use WGS 84 degrees, matching the PostGIS seam. */
    public record GpsCoordinates(double latitude, double longitude) {
        public GpsCoordinates {
            if (latitude < -90 || latitude > 90) {
                throw new IllegalArgumentException("latitude must be between -90 and 90");
            }
            if (longitude < -180 || longitude > 180) {
                throw new IllegalArgumentException("longitude must be between -180 and 180");
            }
        }
    }
}
