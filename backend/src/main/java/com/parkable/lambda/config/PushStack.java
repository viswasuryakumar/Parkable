package com.parkable.lambda.config;

import com.parkable.push.PushSubscriptionRepository;
import com.parkable.push.ReminderScheduler;
import com.parkable.push.VapidSigner;
import com.parkable.push.WebPushSender;
import com.parkable.repository.postgres.PostgresPushSubscriptionRepository;
import software.amazon.awssdk.services.scheduler.SchedulerClient;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * Composition root for the Web Push feature - same shape as
 * {@link ReportStorageStack}, wired from environment variables the SAM
 * template fills from SSM at deploy time.
 *
 * Unlike the other stacks there is no in-memory fallback: a push reminder
 * that silently goes nowhere is worse than an honest failure, because the
 * user has been told their phone will warn them before the meter runs out.
 */
public final class PushStack {

    private final PushSubscriptionRepository repository;
    private final WebPushSender sender;
    private final ReminderScheduler scheduler;

    private PushStack(PushSubscriptionRepository repository, WebPushSender sender, ReminderScheduler scheduler) {
        this.repository = repository;
        this.sender = sender;
        this.scheduler = scheduler;
    }

    public PushSubscriptionRepository repository() {
        return repository;
    }

    public WebPushSender sender() {
        return sender;
    }

    public ReminderScheduler scheduler() {
        return scheduler;
    }

    public static PushStack fromEnvironment() {
        return from(System.getenv());
    }

    public static PushStack from(Map<String, String> env) {
        String dbUrl = required(env, "PARKABLE_DB_URL");
        String publicKey = required(env, "PARKABLE_VAPID_PUBLIC_KEY");
        String privateKey = required(env, "PARKABLE_VAPID_PRIVATE_KEY");
        // RFC 8292 asks for a contact the push service can reach; a mailto is
        // the conventional form and costs nothing to provide.
        String subject = Optional.ofNullable(env.get("PARKABLE_VAPID_SUBJECT"))
                .filter(value -> !value.isBlank())
                .orElse("mailto:ops@parkable.dev");

        VapidSigner signer;
        try {
            signer = new VapidSigner(privateKey, publicKey, subject, Clock.systemUTC());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("VAPID keys are not a valid P-256 keypair", e);
        }

        ReminderScheduler reminderScheduler = new ReminderScheduler(
                SchedulerClient.create(),
                required(env, "PARKABLE_NOTIFY_FUNCTION_ARN"),
                required(env, "PARKABLE_SCHEDULER_ROLE_ARN"));

        return new PushStack(
                new PostgresPushSubscriptionRepository(dbUrl),
                new WebPushSender(signer),
                reminderScheduler);
    }

    /** Notify-side wiring only: sending needs no scheduler, so its ARNs aren't required. */
    public static PushStack forNotifying(Map<String, String> env) {
        String dbUrl = required(env, "PARKABLE_DB_URL");
        String publicKey = required(env, "PARKABLE_VAPID_PUBLIC_KEY");
        String privateKey = required(env, "PARKABLE_VAPID_PRIVATE_KEY");
        String subject = Optional.ofNullable(env.get("PARKABLE_VAPID_SUBJECT"))
                .filter(value -> !value.isBlank())
                .orElse("mailto:ops@parkable.dev");
        try {
            return new PushStack(
                    new PostgresPushSubscriptionRepository(dbUrl),
                    new WebPushSender(new VapidSigner(privateKey, publicKey, subject, Clock.systemUTC())),
                    null);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("VAPID keys are not a valid P-256 keypair", e);
        }
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for Web Push");
        }
        return value;
    }
}
