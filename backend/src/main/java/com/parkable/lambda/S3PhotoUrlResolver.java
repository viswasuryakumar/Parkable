package com.parkable.lambda;

import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Same 1-hour presign window as {@link S3PhotoUploader} and the same reason
 * (Lambda's execution-role credentials, which presigning relies on, don't
 * live longer than that) - a fresh URL generated at read time still expires
 * this soon, it just means "check/nearby show a working link for about an
 * hour after whoever calls them," not "forever."
 */
final class S3PhotoUrlResolver implements PhotoUrlResolver {

    private static final Duration PRESIGN_DURATION = Duration.ofHours(1);

    private final S3Presigner presigner;
    private final String bucket;

    S3PhotoUrlResolver(S3Presigner presigner, String bucket) {
        this.presigner = Objects.requireNonNull(presigner, "presigner");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    /** No PARKABLE_PHOTO_BUCKET configured (e.g. local dev) -> resolving is a no-op, never a failure. */
    static PhotoUrlResolver fromEnvironment() {
        String bucket = System.getenv("PARKABLE_PHOTO_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            return photoReference -> Optional.empty();
        }
        return new S3PhotoUrlResolver(S3Presigner.create(), bucket);
    }

    @Override
    public Optional<String> resolve(String photoReference) {
        try {
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(PRESIGN_DURATION)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(photoReference).build())
                    .build();
            return Optional.of(presigner.presignGetObject(presignRequest).url().toString());
        } catch (RuntimeException e) {
            // A thumbnail is a nice-to-have; losing it must never fail an
            // otherwise-successful /check or /nearby response.
            return Optional.empty();
        }
    }
}
