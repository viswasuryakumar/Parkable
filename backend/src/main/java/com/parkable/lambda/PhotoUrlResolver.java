package com.parkable.lambda;

import java.util.Optional;

/**
 * Turns a stable S3 key (StoredRule.photoReference) into a viewable URL,
 * generated fresh per request rather than trusting a URL baked in at scan
 * time - photo_url expires (see S3PhotoUploader's 1h presign window), so a
 * rule looked up later needs a new one, not the one from when it was scanned.
 */
interface PhotoUrlResolver {
    Optional<String> resolve(String photoReference);
}
