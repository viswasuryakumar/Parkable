import React from 'react';
import { ActivityIndicator, Button, StyleSheet, Text, View } from 'react-native';
import * as Location from 'expo-location';
import { CheckResult, checkParking } from '../services/api';
import VerdictSummary from '../components/VerdictSummary';

type VerdictScreenProps = {
  onScanRequested: () => void;
};

type ScreenState =
  | { phase: 'locating' }
  | { phase: 'checking' }
  | { phase: 'result'; result: CheckResult }
  | { phase: 'error'; message: string };

export default function VerdictScreen({ onScanRequested }: VerdictScreenProps) {
  const [state, setState] = React.useState<ScreenState>({ phase: 'locating' });

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
        <Text style={styles.note}>{state.message}</Text>
        <Button title="Try again" onPress={runCheck} />
      </View>
    );
  }

  if (state.result.kind === 'no_data') {
    return (
      <View style={styles.container}>
        <Text style={styles.title}>No parking data here yet</Text>
        <Text style={styles.note}>{state.result.message}</Text>
        <Button title="Scan the sign" onPress={onScanRequested} />
        <Button title="Check again" onPress={runCheck} />
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <VerdictSummary verdict={state.result.verdict} />
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
  note: {
    color: '#6b7280',
    textAlign: 'center',
  },
});
