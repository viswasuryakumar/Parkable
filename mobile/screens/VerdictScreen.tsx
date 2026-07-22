import React from 'react';
import { ActivityIndicator, Button, Platform, StyleSheet, Text, View } from 'react-native';
import * as Location from 'expo-location';
import { CheckResult, VerdictResponse, checkParking } from '../services/api';
import VerdictSummary from '../components/VerdictSummary';

type VerdictScreenProps = {
  onScanRequested: () => void;
};

type ScreenState =
  | { phase: 'locating' }
  | { phase: 'checking' }
  | { phase: 'result'; result: CheckResult; lat: number; lng: number }
  | { phase: 'error'; message: string };

// expo-location's web shim always throws on reverse geocoding - the street
// line is a native-only nicety, not something the flow depends on.
const REVERSE_GEOCODE_SUPPORTED = Platform.OS !== 'web';

export default function VerdictScreen({ onScanRequested }: VerdictScreenProps) {
  const [state, setState] = React.useState<ScreenState>({ phase: 'locating' });
  const [street, setStreet] = React.useState<string | null>(null);
  const [timerStarted, setTimerStarted] = React.useState(false);
  const [startingTimer, setStartingTimer] = React.useState(false);

  const runCheck = React.useCallback(async () => {
    setState({ phase: 'locating' });
    setStreet(null);
    setTimerStarted(false);
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
      const { latitude, longitude } = position.coords;
      setState({ phase: 'checking' });
      const result = await checkParking(latitude, longitude);
      setState({ phase: 'result', result, lat: latitude, lng: longitude });

      if (REVERSE_GEOCODE_SUPPORTED) {
        Location.reverseGeocodeAsync({ latitude, longitude })
          .then((results) => setStreet(results[0]?.street ?? null))
          .catch(() => {
            // Honest degrade: the verdict itself doesn't need this to work.
          });
      }
    } catch (e) {
      setState({ phase: 'error', message: e instanceof Error ? e.message : 'Unknown error' });
    }
  }, []);

  // Auto-check on mount: opening the app IS the question "can I park here?".
  React.useEffect(() => {
    runCheck();
  }, [runCheck]);

  async function startTimer() {
    if (state.phase !== 'result') {
      return;
    }
    setStartingTimer(true);
    try {
      // Re-check at THIS instant (when you actually park), not the moment
      // the screen first loaded - the two are rarely the same.
      const result = await checkParking(state.lat, state.lng);
      setState({ phase: 'result', result, lat: state.lat, lng: state.lng });
      setTimerStarted(true);
    } catch {
      // Leave the prior verdict on screen; the button just stays available to retry.
    } finally {
      setStartingTimer(false);
    }
  }

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
        <Text style={styles.note}>{state.message}</Text>
        <Button title="Try again" onPress={runCheck} />
      </View>
    );
  }

  if (state.result.kind === 'no_data') {
    return (
      <View style={styles.container}>
        {street ? <Text style={styles.location}>Near {street}</Text> : null}
        <Text style={styles.title}>No parking data here yet</Text>
        <Text style={styles.note}>{state.result.message}</Text>
        <Button title="Scan the sign" onPress={onScanRequested} />
        <Button title="Check again" onPress={runCheck} />
      </View>
    );
  }

  const verdict: VerdictResponse = state.result.verdict;

  return (
    <View style={styles.container}>
      {street ? <Text style={styles.location}>Near {street}</Text> : null}
      <VerdictSummary
        verdict={verdict}
        onStartTimer={startTimer}
        timerStarted={timerStarted}
        startingTimer={startingTimer}
      />
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
  location: {
    fontSize: 13,
    color: '#2563eb',
    fontWeight: '500',
  },
  note: {
    color: '#6b7280',
    textAlign: 'center',
  },
});
