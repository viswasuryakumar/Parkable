package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.parkable.lambda.config.PushStack;
import com.parkable.push.PushSubscription;
import com.parkable.push.PushSubscriptionRepository;
import com.parkable.push.ReminderScheduler.ReminderKind;
import com.parkable.push.WebPushSender;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Invoked by EventBridge Scheduler when a parking reminder comes due - not by
 * API Gateway, so its input is the plain JSON the schedule carries rather
 * than an HTTP event.
 *
 * Sends exactly one push and reports what happened. It does not retry: the
 * push service already queues for an offline device (see the TTL in
 * {@link WebPushSender}), and a reminder that arrives late enough to need a
 * retry is no longer worth delivering.
 */
public class PushNotifyHandler implements RequestHandler<Map<String, Object>, String> {

    private final PushSubscriptionRepository repository;
    private final WebPushSender sender;

    public PushNotifyHandler() {
        PushStack stack = PushStack.forNotifying(System.getenv());
        this.repository = stack.repository();
        this.sender = stack.sender();
    }

    public PushNotifyHandler(PushSubscriptionRepository repository, WebPushSender sender) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        UUID subscriptionId;
        try {
            subscriptionId = UUID.fromString(String.valueOf(input.get("subscription_id")));
        } catch (IllegalArgumentException e) {
            return "INVALID_INPUT";
        }
        ReminderKind kind = parseKind(input.get("kind"));

        Optional<PushSubscription> subscription = repository.findById(subscriptionId);
        if (subscription.isEmpty()) {
            // The car was moved and the subscription cleaned up between the
            // schedule firing and this running. Nothing to do, not an error.
            return "NO_SUBSCRIPTION";
        }

        WebPushSender.Result result = sender.send(subscription.get(), payloadFor(kind));
        if (result == WebPushSender.Result.SUBSCRIPTION_GONE) {
            repository.delete(subscriptionId);
        }
        return result.name();
    }

    private static ReminderKind parseKind(Object raw) {
        try {
            return ReminderKind.valueOf(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            // An unrecognised kind still means a reminder is due; the deadline
            // wording is the safe default because it never overstates time left.
            return ReminderKind.DEADLINE;
        }
    }

    private static String payloadFor(ReminderKind kind) {
        return switch (kind) {
            case WARNING -> """
                    {"title":"Move your car soon","body":"Your parking time limit is almost up.","tag":"parkable-warning"}""";
            case DEADLINE -> """
                    {"title":"Your parking time is up","body":"The time limit where you parked has expired. Move your car to avoid a ticket.","tag":"parkable-deadline"}""";
        };
    }
}
