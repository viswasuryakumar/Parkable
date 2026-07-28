import React from 'react';
import { ActivityIndicator, Animated, Image, Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import * as Haptics from 'expo-haptics';
import { VerdictResponse } from '../services/api';
import { useTheme, VERDICT_COLOR_KEYS } from '../theme/colors';

const HAPTIC_FEEDBACK: Record<string, Haptics.NotificationFeedbackType> = {
  PARKABLE: Haptics.NotificationFeedbackType.Success,
  NOT_PARKABLE: Haptics.NotificationFeedbackType.Error,
  DEPENDS: Haptics.NotificationFeedbackType.Warning,
};

const VERDICT_HEADLINES: Record<string, string> = {
  PARKABLE: 'You can park here',
  NOT_PARKABLE: 'Do not park here',
  DEPENDS: 'It depends',
};

function formatCountdown(msRemaining: number): string {
  const totalMinutes = Math.floor(msRemaining / 60_000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours >= 24) {
    const days = Math.floor(hours / 24);
    return `${days}d ${hours % 24}h`;
  }
  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  return `${minutes}m`;
}

/** Ticks once a minute so the valid-until countdown stays honest without re-fetching. */
function useNow(): number {
  const [now, setNow] = React.useState(Date.now());
  React.useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(timer);
  }, []);
  return now;
}

type VerdictSummaryProps = {
  verdict: VerdictResponse;
  /**
   * When provided, a time-limited PARKABLE verdict shows a "Start Parking
   * Timer" button instead of an immediate countdown - scan/check time and
   * "I actually parked" time are rarely the same moment (you still have to
   * walk back and get in the spot), so the countdown must anchor to a
   * button press, not the server response that happened to arrive first.
   * The callback re-fetches the verdict at press time and should call back
   * with the fresh result via a state update in the parent.
   */
  onStartTimer?: () => Promise<void>;
  timerStarted?: boolean;
  startingTimer?: boolean;
  /** Shown near the headline; lets the driver flag "this looks wrong/outdated." */
  onReport?: () => void;
};

/**
 * The one way a verdict is displayed anywhere in the app: headline, reason,
 * live countdown to valid_until, and data-source label. Check tab and scan
 * results must never drift apart in what they tell the driver.
 */
export default function VerdictSummary({
  verdict,
  onStartTimer,
  timerStarted = false,
  startingTimer = false,
  onReport,
}: VerdictSummaryProps) {
  const now = useNow();
  const theme = useTheme();
  const [traceExpanded, setTraceExpanded] = React.useState(false);
  const colorKey = VERDICT_COLOR_KEYS[verdict.verdict];
  const color = colorKey ? theme[colorKey] : theme.text;
  const validUntil = verdict.valid_until ? Date.parse(verdict.valid_until) : null;
  const msRemaining = validUntil === null ? null : validUntil - now;
  const isTimeLimited = verdict.verdict === 'PARKABLE' && validUntil !== null;
  const awaitingTimerStart = isTimeLimited && Boolean(onStartTimer) && !timerStarted;

  const reveal = React.useRef(new Animated.Value(0)).current;
  React.useEffect(() => {
    reveal.setValue(0);
    Animated.spring(reveal, { toValue: 1, useNativeDriver: true, friction: 6 }).start();
    if (Platform.OS !== 'web') {
      const feedback = HAPTIC_FEEDBACK[verdict.verdict];
      if (feedback !== undefined) {
        Haptics.notificationAsync(feedback).catch(() => {});
      }
    }
    // Only the verdict identity should retrigger the reveal - re-renders
    // from the countdown ticking every minute must not replay the animation.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [verdict.verdict, verdict.rule_id]);

  return (
    <Animated.View
      style={[
        styles.container,
        {
          opacity: reveal,
          transform: [{ scale: reveal.interpolate({ inputRange: [0, 1], outputRange: [0.9, 1] }) }],
        },
      ]}
    >
      {verdict.photo_url ? (
        <Image source={{ uri: verdict.photo_url }} style={styles.photo} resizeMode="cover" />
      ) : null}
      <Text style={[styles.verdict, { color }]}>
        {VERDICT_HEADLINES[verdict.verdict] ?? verdict.verdict}
      </Text>
      {typeof verdict.confidence === 'number' ? (
        <View style={[styles.confidenceBadge, { backgroundColor: theme.card }]}>
          <Text style={[styles.confidenceText, { color: theme.textMuted }]}>
            {Math.round(verdict.confidence * 100)}% confident read
          </Text>
        </View>
      ) : null}
      {verdict.reason ? (
        <Text style={[styles.reason, { color: theme.textMuted }]}>{verdict.reason}</Text>
      ) : null}

      {awaitingTimerStart ? (
        <View style={styles.timerPrompt}>
          <Text style={[styles.note, { color: theme.textMuted }]}>
            Tap Start the moment you actually park — the countdown is timed from then, not from
            now.
          </Text>
          {startingTimer ? (
            <ActivityIndicator color={theme.accent} />
          ) : (
            <Pressable
              style={({ pressed }) => [
                styles.primaryButton,
                { backgroundColor: theme.accent, opacity: pressed ? 0.85 : 1 },
              ]}
              onPress={onStartTimer}
            >
              <Text style={styles.primaryButtonText}>Start Parking Timer</Text>
            </Pressable>
          )}
        </View>
      ) : null}

      {!awaitingTimerStart && msRemaining !== null && msRemaining > 0 ? (
        <Text style={[styles.countdown, { color: theme.text }]}>
          {verdict.verdict === 'PARKABLE'
            ? `Move your car within ${formatCountdown(msRemaining)}`
            : verdict.verdict === 'NOT_PARKABLE'
              ? `This restriction lifts in ${formatCountdown(msRemaining)}`
              : `This may change in ${formatCountdown(msRemaining)}`}
        </Text>
      ) : null}
      {!awaitingTimerStart && msRemaining !== null && msRemaining <= 0 ? (
        <Text style={[styles.countdown, { color: theme.text }]}>
          This verdict may be stale — check again.
        </Text>
      ) : null}

      {verdict.source ? (
        <Text style={[styles.source, { color: theme.textMuted }]}>
          {verdict.source === 'gov_data'
            ? 'Source: official city data'
            : 'Source: community sign scan'}
        </Text>
      ) : null}

      {verdict.trace && verdict.trace.length > 0 ? (
        <View style={[styles.traceCard, { backgroundColor: theme.card, borderColor: theme.border }]}>
          <Pressable
            style={styles.traceToggleRow}
            onPress={() => setTraceExpanded((prev) => !prev)}
            hitSlop={8}
          >
            <Text style={[styles.traceToggleText, { color: theme.text }]}>Why this verdict?</Text>
            <Text style={[styles.traceChevron, { color: theme.textMuted }]}>{traceExpanded ? '▲' : '▼'}</Text>
          </Pressable>
          {traceExpanded ? (
            <View style={[styles.traceBody, { borderTopColor: theme.border }]}>
              {verdict.trace.map((line, index) => (
                <Text key={index} style={[styles.traceLine, { color: theme.textMuted }]}>
                  {line}
                </Text>
              ))}
            </View>
          ) : null}
        </View>
      ) : null}

      {onReport ? (
        <Pressable
          style={({ pressed }) => [
            styles.reportButton,
            { borderColor: theme.border, backgroundColor: pressed ? theme.card : 'transparent' },
          ]}
          onPress={onReport}
          hitSlop={8}
        >
          <Text style={styles.reportIcon}>🚩</Text>
          <Text style={[styles.reportButtonText, { color: theme.textMuted }]}>Report an issue with this sign</Text>
        </Pressable>
      ) : null}
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
    gap: 12,
  },
  verdict: {
    fontSize: 32,
    fontWeight: '700',
    textAlign: 'center',
  },
  reason: {
    textAlign: 'center',
    fontSize: 16,
  },
  timerPrompt: {
    alignItems: 'center',
    gap: 8,
  },
  note: {
    textAlign: 'center',
    fontSize: 13,
  },
  countdown: {
    fontSize: 18,
    fontWeight: '600',
  },
  source: {
    fontSize: 13,
  },
  photo: {
    width: 220,
    height: 160,
    borderRadius: 12,
  },
  confidenceBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  confidenceText: {
    fontSize: 12,
    fontWeight: '600',
  },
  primaryButton: {
    borderRadius: 10,
    paddingVertical: 14,
    paddingHorizontal: 28,
    alignItems: 'center',
  },
  primaryButtonText: {
    color: '#ffffff',
    fontSize: 16,
    fontWeight: '700',
  },
  traceCard: {
    width: '100%',
    borderRadius: 12,
    borderWidth: 1,
    overflow: 'hidden',
  },
  traceToggleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 12,
    paddingHorizontal: 14,
  },
  traceToggleText: {
    fontSize: 14,
    fontWeight: '600',
  },
  traceChevron: {
    fontSize: 11,
  },
  traceBody: {
    borderTopWidth: 1,
    padding: 14,
    gap: 6,
  },
  traceLine: {
    fontSize: 12,
    lineHeight: 17,
  },
  reportButton: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    borderWidth: 1,
    borderRadius: 20,
    paddingVertical: 8,
    paddingHorizontal: 16,
  },
  reportIcon: {
    fontSize: 13,
  },
  reportButtonText: {
    fontSize: 13,
    fontWeight: '600',
  },
});
