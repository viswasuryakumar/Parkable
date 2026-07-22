import React from 'react';
import { ActivityIndicator, Button, StyleSheet, Text, View } from 'react-native';
import * as Location from 'expo-location';
import { CheckResult, checkParking } from '../services/api';

type VerdictScreenProps = {
  onScanRequested: () => void;
};

type ScreenState =
  | { phase: 'locating' }
  | { phase: 'checking' }
  | { phase: 'result'; result: CheckResult }
  | { phase: 'error'; message: string };

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

export default function VerdictScreen({ onScanRequested }: VerdictScreenProps) {
  const [state, setState] = React.useState<ScreenState>({ phase: 'locating' });
  const now = useNow();

  const runCheck = React.useCallback(async () => {
    setState({ phase: 'locating' });
    try {
      const permission = await Location.requestForegroundPermissionsAsync();
      if (permission.status !== 'granted') {
        setState({
          phase: 'error',
          message: 'Location permission is required to check parking where you are.',
        });
        return;
      }
      const position = await Location.getCurrentPositionAsync({
        accuracy: Location.Accuracy.Balanced,
      });
      setState({ phase: 'checking' });
      const result = await checkParking(position.coords.latitude, position.coords.longitude);
      setState({ phase: 'result', result });
    } catch (e) {
      setState({ phase: 'error', message: e instanceof Error ? e.message : 'Unknown error' });
    }
  }, []);

  // Auto-check on mount: opening the app IS the question "can I park here?".
  React.useEffect(() => {
    runCheck();
  }, [runCheck]);

  if (state.phase === 'locating' || state.phase === 'checking') {
    return (
      <View style={styles.container}>
        <ActivityIndicator size="large" />
        <Text style={styles.title}>
          {state.phase === 'locating' ? 'Finding your location…' : 'Checking parking rules…'}
        </Text>
      </View>
    );
  }

  if (state.phase === 'error') {
    return (
      <View style={styles.container}>
        <Text style={styles.title}>Something went wrong</Text>
        <Text style={styles.reason}>{state.message}</Text>
        <Button title="Try again" onPress={runCheck} />
      </View>
    );
  }

  if (state.result.kind === 'no_data') {
    return (
      <View style={styles.container}>
        <Text style={styles.title}>No parking data here yet</Text>
        <Text style={styles.reason}>{state.result.message}</Text>
        <Button title="Scan the sign" onPress={onScanRequested} />
        <Button title="Check again" onPress={runCheck} />
      </View>
    );
  }

  const verdict = state.result.verdict;
  const color = VERDICT_COLORS[verdict.verdict] ?? '#111827';
  const validUntil = verdict.valid_until ? Date.parse(verdict.valid_until) : null;
  const msRemaining = validUntil === null ? null : validUntil - now;

  return (
    <View style={styles.container}>
      <Text style={[styles.verdict, { color }]}>
        {VERDICT_HEADLINES[verdict.verdict] ?? verdict.verdict}
      </Text>
      {verdict.reason ? <Text style={styles.reason}>{verdict.reason}</Text> : null}
      {msRemaining !== null && msRemaining > 0 ? (
        <Text style={styles.countdown}>
          {verdict.verdict === 'PARKABLE'
            ? `Move your car within ${formatCountdown(msRemaining)}`
            : `Situation changes in ${formatCountdown(msRemaining)}`}
        </Text>
      ) : null}
      {msRemaining !== null && msRemaining <= 0 ? (
        <Text style={styles.countdown}>This verdict may be stale — check again.</Text>
      ) : null}
      {verdict.source ? (
        <Text style={styles.source}>
          {verdict.source === 'gov_data'
            ? 'Source: official city data'
            : 'Source: community sign scan'}
        </Text>
      ) : null}
      <Button title="Check again" onPress={runCheck} />
      <Button title="Scan a sign instead" onPress={onScanRequested} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
    gap: 12,
  },
  title: {
    fontSize: 24,
    fontWeight: '600',
    textAlign: 'center',
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
