package com.parkable.push;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * One browser's Web Push registration, exactly as {@code PushManager.subscribe()}
 * hands it back: where to deliver ({@code endpoint}) and the two keys the
 * payload must be encrypted to.
 *
 * @param p256dh the browser's public key, unpadded base64url of an uncompressed P-256 point
 * @param auth   the 16-byte shared authentication secret, unpadded base64url
 */
public record PushSubscription(UUID id, URI endpoint, String p256dh, String auth) {

    public PushSubscription {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(p256dh, "p256dh");
        Objects.requireNonNull(auth, "auth");
    }

    byte[] decodedP256dh() {
        return P256.decodeBase64Url(p256dh);
    }

    byte[] decodedAuth() {
        return P256.decodeBase64Url(auth);
    }
}
