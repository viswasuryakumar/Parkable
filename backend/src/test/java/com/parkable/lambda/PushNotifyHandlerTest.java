package com.parkable.lambda;

import com.parkable.push.PushSubscription;
import com.parkable.push.PushSubscriptionRepository;
import com.parkable.push.WebPushSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushNotifyHandlerTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final PushSubscription SUBSCRIPTION =
            new PushSubscription(ID, URI.create("https://fcm.googleapis.com/fcm/send/abc"), "key", "secret");

    @Test
    @DisplayName("sends the deadline wording when the meter has expired")
    void sendsDeadlineNotification() {
        PushSubscriptionRepository repository = mock(PushSubscriptionRepository.class);
        WebPushSender sender = mock(WebPushSender.class);
        when(repository.findById(ID)).thenReturn(Optional.of(SUBSCRIPTION));
        when(sender.send(any(), any())).thenReturn(WebPushSender.Result.DELIVERED);

        String result = new PushNotifyHandler(repository, sender)
                .handleRequest(Map.of("subscription_id", ID.toString(), "kind", "DEADLINE"), null);

        assertThat(result).isEqualTo("DELIVERED");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(SUBSCRIPTION), payload.capture());
        assertThat(payload.getValue()).contains("Your parking time is up");
    }

    @Test
    @DisplayName("sends the warning wording ahead of the deadline")
    void sendsWarningNotification() {
        PushSubscriptionRepository repository = mock(PushSubscriptionRepository.class);
        WebPushSender sender = mock(WebPushSender.class);
        when(repository.findById(ID)).thenReturn(Optional.of(SUBSCRIPTION));
        when(sender.send(any(), any())).thenReturn(WebPushSender.Result.DELIVERED);

        new PushNotifyHandler(repository, sender)
                .handleRequest(Map.of("subscription_id", ID.toString(), "kind", "WARNING"), null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(sender).send(any(), payload.capture());
        assertThat(payload.getValue()).contains("Move your car soon");
    }

    @Test
    @DisplayName("forgets a subscription the push service reports as gone")
    void deletesDeadSubscription() {
        PushSubscriptionRepository repository = mock(PushSubscriptionRepository.class);
        WebPushSender sender = mock(WebPushSender.class);
        when(repository.findById(ID)).thenReturn(Optional.of(SUBSCRIPTION));
        when(sender.send(any(), any())).thenReturn(WebPushSender.Result.SUBSCRIPTION_GONE);

        String result = new PushNotifyHandler(repository, sender)
                .handleRequest(Map.of("subscription_id", ID.toString(), "kind", "DEADLINE"), null);

        assertThat(result).isEqualTo("SUBSCRIPTION_GONE");
        verify(repository).delete(ID);
    }

    @Test
    @DisplayName("a cleared session between scheduling and firing is not an error")
    void toleratesMissingSubscription() {
        PushSubscriptionRepository repository = mock(PushSubscriptionRepository.class);
        WebPushSender sender = mock(WebPushSender.class);
        when(repository.findById(ID)).thenReturn(Optional.empty());

        String result = new PushNotifyHandler(repository, sender)
                .handleRequest(Map.of("subscription_id", ID.toString(), "kind", "DEADLINE"), null);

        assertThat(result).isEqualTo("NO_SUBSCRIPTION");
        verify(sender, never()).send(any(), any());
    }

    @Test
    @DisplayName("a garbled subscription id fails fast rather than throwing")
    void rejectsMalformedId() {
        String result = new PushNotifyHandler(mock(PushSubscriptionRepository.class), mock(WebPushSender.class))
                .handleRequest(Map.of("subscription_id", "not-a-uuid", "kind", "DEADLINE"), null);

        assertThat(result).isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("an unrecognised kind falls back to deadline wording, never overstating time left")
    void unknownKindFallsBackToDeadline() {
        PushSubscriptionRepository repository = mock(PushSubscriptionRepository.class);
        WebPushSender sender = mock(WebPushSender.class);
        when(repository.findById(ID)).thenReturn(Optional.of(SUBSCRIPTION));
        when(sender.send(any(), any())).thenReturn(WebPushSender.Result.DELIVERED);

        new PushNotifyHandler(repository, sender)
                .handleRequest(Map.of("subscription_id", ID.toString(), "kind", "SOMETHING_ELSE"), null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(sender).send(any(), payload.capture());
        assertThat(payload.getValue()).contains("Your parking time is up");
    }
}
