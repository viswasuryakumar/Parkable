package com.parkable.lambda;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Uploads scan photos to the PhotosBucket (blocks all public access - see
 * infra/template.yaml) and returns a presigned GET URL so the client can
 * display a thumbnail without the bucket ever being public.
 *
 * <p>The presign window is intentionally short (1 hour), not SigV4's 7-day
 * max: inside Lambda, presigning uses the execution role's own temporary,
 * periodically-rotated credentials, so a URL signed for longer than those
 * credentials' remaining lifetime would silently stop working anyway -
 * asking for 7 days would overpromise. Good for "see the photo you just
 * scanned"; NOT durable enough for showing a thumbnail in scan history
 * days later (a real, accepted limitation, not an oversight).
 */
final class S3PhotoUploader implements ScanHandler.PhotoUploader {

    private static final Duration PRESIGN_DURATION = Duration.ofHours(1);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    S3PhotoUploader(S3Client s3, S3Presigner presigner, String bucket) {
        this.s3 = Objects.requireNonNull(s3, "s3");
        this.presigner = Objects.requireNonNull(presigner, "presigner");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    /** No PARKABLE_PHOTO_BUCKET configured (e.g. local dev) -> upload is a no-op, never a failure. */
    static ScanHandler.PhotoUploader fromEnvironment() {
        String bucket = System.getenv("PARKABLE_PHOTO_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            return (bytes, mediaType, key) -> Optional.empty();
        }
        return new S3PhotoUploader(S3Client.create(), S3Presigner.create(), bucket);
    }

    @Override
    public Optional<String> upload(byte[] photoBytes, String mediaType, String key) {
        try {
            s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType(mediaType).build(),
                    RequestBody.fromBytes(photoBytes));
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(PRESIGN_DURATION)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .build();
            return Optional.of(presigner.presignGetObject(presignRequest).url().toString());
        } catch (RuntimeException e) {
            // A thumbnail is a nice-to-have; losing it must never fail an
            // otherwise-successful scan (honest degrade, same principle as
            // the reverse-geocode/street-name lookups on the mobile side).
            return Optional.empty();
        }
    }
}
