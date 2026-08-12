package com.parkable.push;

import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.ActionAfterCompletion;
import software.amazon.awssdk.services.scheduler.model.ConflictException;
import software.amazon.awssdk.services.scheduler.model.CreateScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.DeleteScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindow;
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindowMode;
import software.amazon.awssdk.services.scheduler.model.ResourceNotFoundException;
import software.amazon.awssdk.services.scheduler.model.Target;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Creates the one-shot EventBridge schedules that fire a parking reminder.
 *
 * Two per session: a warning with time left to act on it, and a backstop at
 * the deadline itself. Schedules are named deterministically from the
 * subscription id so cancelling needs no extra bookkeeping table - the names
 * are recomputable from the subscription alone.
 *
 * Each schedule carries {@code ActionAfterCompletion=DELETE}, so a fired
 * reminder cleans itself up rather than accumulating dead schedules against
 * the account's quota.
 */
public final class ReminderScheduler {

    /** Matches the native app's 10-minute warning so both platforms behave the same. */
    static final Duration WARNING_LEAD = Duration.ofMinutes(10);

    /** EventBridge wants a local date-time with no offset, paired with an explicit timezone. */
    private static final DateTimeFormatter AT_EXPRESSION =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final SchedulerClient scheduler;
    private final String targetFunctionArn;
    private final String schedulerRoleArn;

    public ReminderScheduler(SchedulerClient scheduler, String targetFunctionArn, String schedulerRoleArn) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.targetFunctionArn = Objects.requireNonNull(targetFunctionArn, "targetFunctionArn");
        this.schedulerRoleArn = Objects.requireNonNull(schedulerRoleArn, "schedulerRoleArn");
    }

    /**
     * Replaces any reminders already set for this subscription with ones for
     * {@code deadline}. Returns how many were actually created - a limit
     * shorter than the warning lead legitimately yields just the one.
     */
    public int schedule(UUID subscriptionId, Instant deadline, Instant now) {
        cancel(subscriptionId);

        int created = 0;
        Instant warningAt = deadline.minus(WARNING_LEAD);
        // Only worth sending if it still leaves time to act; on a 5-minute
        // limit the warning instant is already in the past.
        if (warningAt.isAfter(now)) {
            createSchedule(warningName(subscriptionId), warningAt, subscriptionId, ReminderKind.WARNING);
            created++;
        }
        if (deadline.isAfter(now)) {
            createSchedule(deadlineName(subscriptionId), deadline, subscriptionId, ReminderKind.DEADLINE);
            created++;
        }
        return created;
    }

    /** Removes both reminders. Safe to call when none exist - the car may simply have moved early. */
    public void cancel(UUID subscriptionId) {
        for (String name : List.of(warningName(subscriptionId), deadlineName(subscriptionId))) {
            try {
                scheduler.deleteSchedule(DeleteScheduleRequest.builder().name(name).build());
            } catch (ResourceNotFoundException e) {
                // Never scheduled, or already fired and self-deleted.
            }
        }
    }

    private void createSchedule(String name, Instant fireAt, UUID subscriptionId, ReminderKind kind) {
        String input = """
                {"subscription_id":"%s","kind":"%s"}""".formatted(subscriptionId, kind.name());
        try {
            scheduler.createSchedule(CreateScheduleRequest.builder()
                    .name(name)
                    .scheduleExpression("at(" + AT_EXPRESSION.format(fireAt) + ")")
                    .scheduleExpressionTimezone("UTC")
                    // Fire at the stated time, not inside a smoothing window -
                    // "your meter expired" is only useful when it's punctual.
                    .flexibleTimeWindow(FlexibleTimeWindow.builder().mode(FlexibleTimeWindowMode.OFF).build())
                    .actionAfterCompletion(ActionAfterCompletion.DELETE)
                    .target(Target.builder()
                            .arn(targetFunctionArn)
                            .roleArn(schedulerRoleArn)
                            .input(input)
                            .build())
                    .build());
        } catch (ConflictException e) {
            // A concurrent start for the same subscription won the race; its
            // schedule is equally valid, so leave it alone.
        }
    }

    /** Which of the two reminders fired, so the notify Lambda can word it correctly. */
    public enum ReminderKind {
        WARNING,
        DEADLINE
    }

    private static String warningName(UUID subscriptionId) {
        return "parkable-" + compact(subscriptionId) + "-warn";
    }

    private static String deadlineName(UUID subscriptionId) {
        return "parkable-" + compact(subscriptionId) + "-due";
    }

    /** Schedule names allow [0-9a-zA-Z-_.] only and cap at 64 chars; 9 + 32 + 5 fits. */
    private static String compact(UUID subscriptionId) {
        return subscriptionId.toString().replace("-", "");
    }
}
