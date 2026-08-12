import React from 'react';
import { ActivityIndicator, Animated, Image, Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import * as Haptics from 'expo-haptics';
import { VerdictResponse } from '../services/api';
import { useTheme, SPACING, RADIUS, TYPE } from '../theme/colors';
import StatusBanner, { StatusTone } from './StatusBanner';
import Card from './Card';
import Icon from './Icon';
import AppButton from './AppButton';

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

const VERDICT_TONES: Record<string, StatusTone> = {
  PARKABLE: 'allowed',
  NOT_PARKABLE: 'forbidden',
  DEPENDS: 'conditional',
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
 * The one way a verdict is displayed anywhere in the app: verdict banner,
 * what happens next, why, and where the data came from. Check tab and scan
 * results must never drift apart in what they tell the driver.
 *
 * Structured as banner -> next change -> evidence, in that order, because
 * that is the order the questions actually get asked: "can I park?", then
 * "for how long?", then "says who?". The old version stacked all of it as
 * centred loose text at roughly equal weight, so answering the first
 * question meant reading all three.
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
  const tone = VERDICT_TONES[verdict.verdict] ?? 'neutral';
  const validUntil = verdict.valid_until ? Date.parse(verdict.valid_until) : null;
  const msRemaining = validUntil === null ? null : validUntil - now;
  const isTimeLimited = verdict.verdict === 'PARKABLE' && validUntil !== null;
  const awaitingTimerStart = isTimeLimited && Boolean(onStartTimer) && !timerStarted;

  const reveal = React.useRef(new Animated.Value(0)).current;
  React.useEffect(() => {
    reveal.setValue(0);
    Animated.spring(reveal, { toValue: 1, useNativeDriver: true, friction: 7 }).start();
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

  const changeLabel = React.useMemo(() => {
    if (awaitingTimerStart || msRemaining === null) {
      return null;
    }
    if (msRemaining <= 0) {
      return 'This verdict may be stale — check again.';
    }
    if (verdict.verdict === 'PARKABLE') {
      return `Move your car within ${formatCountdown(msRemaining)}`;
    }
    if (verdict.verdict === 'NOT_PARKABLE') {
      return `This restriction lifts in ${formatCountdown(msRemaining)}`;
    }
    return `This may change in ${formatCountdown(msRemaining)}`;
  }, [awaitingTimerStart, msRemaining, verdict.verdict]);

  return (
    <Animated.View
      style={[
        styles.container,
        {
          opacity: reveal,
          transform: [{ translateY: reveal.interpolate({ inputRange: [0, 1], outputRange: [10, 0] }) }],
        },
      ]}
    >
      <StatusBanner
        tone={tone}
        title={VERDICT_HEADLINES[verdict.verdict] ?? verdict.verdict}
        detail={verdict.reason}
      />

      {/* The scanned sign itself is evidence, so it sits with the verdict
          rather than as a decorative header above it. */}
      {verdict.photo_url ? (
        <Image source={{ uri: verdict.photo_url }} style={styles.photo} resizeMode="cover" />
      ) : null}

      {awaitingTimerStart ? (
        <Card style={styles.section}>
          <View style={styles.sectionHead}>
            <Icon name="timer" size={18} color="accent" />
            <Text style={[styles.sectionTitle, { color: theme.text }]}>Start your timer</Text>
          </View>
          <Text style={[styles.note, { color: theme.textMuted }]}>
            Tap the moment you actually park — the countdown runs from then, not from now.
          </Text>
          <AppButton
            title="Start parking timer"
            icon="timer"
            variant="primary"
            size="lg"
            fullWidth
            loading={startingTimer}
            onPress={() => {
              onStartTimer?.();
            }}
          />
        </Card>
      ) : null}

      {changeLabel ? (
        <Card style={styles.section}>
          <View style={styles.sectionHead}>
            <Icon name="clock" size={18} color="textMuted" />
            <Text style={[styles.sectionTitle, { color: theme.text }]}>Coming up</Text>
          </View>
          <Text style={[styles.changeText, { color: theme.text }]}>{changeLabel}</Text>
        </Card>
      ) : null}

      {/* Confidence and provenance are the trust story - the whole promise of
          this app is that no verdict is a black box - so they get one
          labelled row each instead of two stray grey sentences. */}
      {verdict.source || typeof verdict.confidence === 'number' ? (
        <View style={styles.metaRow}>
          {verdict.source ? (
            <View style={[styles.metaChip, { backgroundColor: theme.surfaceMuted }]}>
              <Icon name="source" size={13} color="textMuted" />
              <Text style={[styles.metaText, { color: theme.textMuted }]}>
                {verdict.source === 'gov_data' ? 'Official city data' : 'Community scan'}
              </Text>
            </View>
          ) : null}
          {typeof verdict.confidence === 'number' ? (
            <View style={[styles.metaChip, { backgroundColor: theme.surfaceMuted }]}>
              <Icon name="info" size={13} color="textMuted" />
              <Text style={[styles.metaText, { color: theme.textMuted }]}>
                {Math.round(verdict.confidence * 100)}% confident read
              </Text>
            </View>
          ) : null}
        </View>
      ) : null}

      {verdict.trace && verdict.trace.length > 0 ? (
        <Card flush>
          <Pressable
            accessibilityRole="button"
            accessibilityState={{ expanded: traceExpanded }}
            style={styles.traceToggleRow}
            onPress={() => setTraceExpanded((prev) => !prev)}
          >
            <Icon name="info" size={18} color="textMuted" />
            <Text style={[styles.sectionTitle, { color: theme.text, flex: 1 }]}>Why this verdict?</Text>
            <Icon name={traceExpanded ? 'chevronUp' : 'chevronDown'} size={16} color="textSubtle" />
          </Pressable>
          {traceExpanded ? (
            <View style={[styles.traceBody, { borderTopColor: theme.border }]}>
              {verdict.trace.map((line, index) => (
                <View key={index} style={styles.traceItem}>
                  <View style={[styles.traceDot, { backgroundColor: theme.borderStrong }]} />
                  <Text style={[styles.traceLine, { color: theme.textMuted }]}>{line}</Text>
                </View>
              ))}
            </View>
          ) : null}
        </Card>
      ) : null}

      <Text style={[styles.disclaimer, { color: theme.textSubtle }]}>
        Always check nearby signs, hydrants and driveways. Real-world conditions may vary.
      </Text>

      {onReport ? (
        <Pressable
          accessibilityRole="button"
          style={({ pressed }) => [styles.reportButton, pressed && { opacity: 0.7 }]}
          onPress={onReport}
          hitSlop={8}
        >
          <Icon name="flag" size={15} color="textMuted" />
          <Text style={[styles.reportButtonText, { color: theme.textMuted }]}>
            Report an issue with this sign
          </Text>
        </Pressable>
      ) : null}
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: SPACING.md,
    width: '100%',
  },
  photo: {
    width: '100%',
    height: 180,
    borderRadius: RADIUS.lg,
  },
  section: {
    padding: SPACING.lg,
    gap: SPACING.sm,
  },
  sectionHead: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.sm,
  },
  sectionTitle: {
    ...TYPE.heading,
  },
  changeText: {
    ...TYPE.bodyStrong,
  },
  note: {
    ...TYPE.body,
  },
  metaRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: SPACING.sm,
  },
  metaChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: SPACING.md,
    paddingVertical: 7,
    borderRadius: RADIUS.pill,
  },
  metaText: {
    ...TYPE.caption,
    fontWeight: '600',
  },
  traceToggleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.sm,
    paddingVertical: SPACING.md,
    paddingHorizontal: SPACING.lg,
  },
  traceBody: {
    borderTopWidth: StyleSheet.hairlineWidth,
    padding: SPACING.lg,
    gap: SPACING.sm,
  },
  traceItem: {
    flexDirection: 'row',
    gap: SPACING.sm,
    alignItems: 'flex-start',
  },
  traceDot: {
    width: 5,
    height: 5,
    borderRadius: 3,
    marginTop: 7,
  },
  traceLine: {
    ...TYPE.caption,
    flex: 1,
  },
  disclaimer: {
    ...TYPE.caption,
    textAlign: 'center',
    paddingHorizontal: SPACING.md,
  },
  reportButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    paddingVertical: SPACING.sm,
  },
  reportButtonText: {
    ...TYPE.label,
  },
});
