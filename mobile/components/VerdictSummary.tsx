import React from 'react';
import { ActivityIndicator, Button, StyleSheet, Text, View } from 'react-native';
import { VerdictResponse } from '../services/api';

const VERDICT_COLORS: Record<string, string> = {
  PARKABLE: '#16a34a',
  NOT_PARKABLE: '#dc2626',
  DEPENDS: '#d97706',
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
}: VerdictSummaryProps) {
  const now = useNow();
  const color = VERDICT_COLORS[verdict.verdict] ?? '#111827';
  const validUntil = verdict.valid_until ? Date.parse(verdict.valid_until) : null;
  const msRemaining = validUntil === null ? null : validUntil - now;
  const isTimeLimited = verdict.verdict === 'PARKABLE' && validUntil !== null;
  const awaitingTimerStart = isTimeLimited && Boolean(onStartTimer) && !timerStarted;

  return (
    <View style={styles.container}>
      <Text style={[styles.verdict, { color }]}>
        {VERDICT_HEADLINES[verdict.verdict] ?? verdict.verdict}
      </Text>
      {verdict.reason ? <Text style={styles.reason}>{verdict.reason}</Text> : null}

      {awaitingTimerStart ? (
        <View style={styles.timerPrompt}>
          <Text style={styles.note}>
            Tap Start the moment you actually park — the countdown is timed from then, not from
            now.
          </Text>
          {startingTimer ? (
            <ActivityIndicator />
          ) : (
            <Button title="Start Parking Timer" onPress={onStartTimer} />
          )}
        </View>
      ) : null}

      {!awaitingTimerStart && msRemaining !== null && msRemaining > 0 ? (
        <Text style={styles.countdown}>
          {verdict.verdict === 'PARKABLE'
            ? `Move your car within ${formatCountdown(msRemaining)}`
            : `Situation changes in ${formatCountdown(msRemaining)}`}
        </Text>
      ) : null}
      {!awaitingTimerStart && msRemaining !== null && msRemaining <= 0 ? (
        <Text style={styles.countdown}>This verdict may be stale — check again.</Text>
      ) : null}

      {verdict.source ? (
        <Text style={styles.source}>
          {verdict.source === 'gov_data'
            ? 'Source: official city data'
            : 'Source: community sign scan'}
        </Text>
      ) : null}
    </View>
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
    color: '#4b5563',
    fontSize: 16,
  },
  timerPrompt: {
    alignItems: 'center',
    gap: 8,
  },
  note: {
    textAlign: 'center',
    color: '#6b7280',
    fontSize: 13,
  },
  countdown: {
    fontSize: 18,
    fontWeight: '600',
    color: '#111827',
  },
  source: {
    color: '#6b7280',
    fontSize: 13,
  },
});
