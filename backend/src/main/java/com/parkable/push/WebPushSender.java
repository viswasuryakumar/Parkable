package com.parkable.push;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Delivers one encrypted message to a push service (FCM, Mozilla autopush,
 * Apple). The push service is a dumb relay - it never sees the plaintext, and
 * it makes no promise about when the browser is next online, only that it will
 * hold the message for up to {@code TTL} seconds.
 */
public final class WebPushSender {

    /**
     * How long the push service may hold an undelivered message. A parking
     * reminder is worthless once it's badly stale - better to drop it than to
     * buzz someone an hour after the meter ran out - but it needs long enough
     * to survive a phone that is briefly offline.
     */
    private static final int TTL_SECONDS = 30 * 60;

    private final HttpClient httpClient;
    private final VapidSigner vapidSigner;

    public WebPushSender(VapidSigner vapidSigner) {
        this(vapidSigner, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    WebPushSender(VapidSigner vapidSigner, HttpClient httpClient) {
        this.vapidSigner = Objects.requireNonNull(vapidSigner, "vapidSigner");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    /** Outcome of a delivery attempt, so the caller knows whether to forget the subscription. */
    public enum Result {
        DELIVERED,
        /** Push service says this endpoint no longer exists - stop retrying it. */
        SUBSCRIPTION_GONE,
        FAILED
    }

    public Result send(PushSubscription subscription, String payloadJson) {
        try {
            byte[] body = WebPushCipher.encrypt(
                    payloadJson.getBytes(StandardCharsets.UTF_8),
                    subscription.decodedP256dh(),
                    subscription.decodedAuth());

            HttpRequest request = HttpRequest.newBuilder(subscription.endpoint())
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", vapidSigner.authorizationHeader(subscription.endpoint()))
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("TTL", Integer.toString(TTL_SECONDS))
                    // Wake the device rather than batching with low-priority
                    // traffic: the whole point is to arrive before a ticket does.
                    .header("Urgency", "high")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return classify(response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (Exception e) {
            // Deliberately no exception detail in the caller's control flow: a
            // failed reminder must never take down the Lambda that also has
            // other reminders to deliver.
            return Result.FAILED;
        }
    }

    private static Result classify(int statusCode) {
        // 404/410 are the two ways a push service says "this subscription is
        // dead" - the user cleared site data, or the browser rotated it.
        if (statusCode == 404 || statusCode == 410) {
            return Result.SUBSCRIPTION_GONE;
        }
        return statusCode >= 200 && statusCode < 300 ? Result.DELIVERED : Result.FAILED;
    }
}
