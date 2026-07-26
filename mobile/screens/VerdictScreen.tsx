import React from 'react';
import { ActivityIndicator, Button, Platform, StyleSheet, Text, View } from 'react-native';
import * as Location from 'expo-location';
import { CompositeNavigationProp, useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { CheckResult, VerdictResponse, checkParking } from '../services/api';
import VerdictSummary from '../components/VerdictSummary';
import { useTheme } from '../theme/colors';
import { addFavorite } from '../utils/favorites';
import { startParkingSession } from '../utils/parkingSession';
import type { RootStackParamList, TabParamList } from '../navigation/types';

type Navigation = CompositeNavigationProp<
  BottomTabNavigationProp<TabParamList>,
  NativeStackNavigationProp<RootStackParamList>
>;

type ScreenState =
  | { phase: 'locating' }
  | { phase: 'checking' }
  | { phase: 'result'; result: CheckResult; lat: number; lng: number }
  | { phase: 'error'; message: string };

// expo-location's web shim always throws on reverse geocoding - the street
// line is a native-only nicety, not something the flow depends on.
const REVERSE_GEOCODE_SUPPORTED = Platform.OS !== 'web';

export default function VerdictScreen() {
  const navigation = useNavigation<Navigation>();
  const theme = useTheme();
  const [state, setState] = React.useState<ScreenState>({ phase: 'locating' });
  const [street, setStreet] = React.useState<string | null>(null);
  const [timerStarted, setTimerStarted] = React.useState(false);
  const [startingTimer, setStartingTimer] = React.useState(false);
  const [favoriteSaved, setFavoriteSaved] = React.useState(false);
  const onScanRequested = React.useCallback(() => navigation.navigate('Scan'), [navigation]);

  const runCheck = React.useCallback(async () => {
    setState({ phase: 'locating' });
    setStreet(null);
    setTimerStarted(false);
    setFavoriteSaved(false);
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
      if (result.kind === 'verdict') {
        startParkingSession(state.lat, state.lng, result.verdict.valid_until ?? null).catch(() => {});
      }
    } catch {
      // Leave the prior verdict on screen; the button just stays available to retry.
    } finally {
      setStartingTimer(false);
    }
  }

  if (state.phase === 'locating' || state.phase === 'checking') {
    return (
      <View style={[styles.container, { backgroundColor: theme.background }]}>
        <ActivityIndicator size="large" />
        <Text style={[styles.title, { color: theme.text }]}>
          {state.phase === 'locating' ? 'Finding your location…' : 'Checking parking rules…'}
        </Text>
      </View>
    );
  }

  if (state.phase === 'error') {
    return (
      <View style={[styles.container, { backgroundColor: theme.background }]}>
        <Text style={[styles.title, { color: theme.text }]}>Something went wrong</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>{state.message}</Text>
        <Button title="Try again" onPress={runCheck} />
      </View>
    );
  }

  if (state.result.kind === 'no_data') {
    return (
      <View style={[styles.container, { backgroundColor: theme.background }]}>
        {street ? <Text style={[styles.location, { color: theme.accent }]}>Near {street}</Text> : null}
        <Text style={[styles.title, { color: theme.text }]}>No parking data here yet</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>{state.result.message}</Text>
        <Button title="Scan the sign" onPress={onScanRequested} />
        <Button title="Check again" onPress={runCheck} />
      </View>
    );
  }

  const verdict: VerdictResponse = state.result.verdict;
  const { lat, lng } = state;

  async function saveFavorite() {
    await addFavorite({
      id: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
      label: street ?? 'Saved spot',
      lat,
      lng,
      savedAt: new Date().toISOString(),
    });
    setFavoriteSaved(true);
  }

  return (
    <View style={[styles.container, { backgroundColor: theme.background }]}>
      {street ? <Text style={[styles.location, { color: theme.accent }]}>Near {street}</Text> : null}
      <VerdictSummary
        verdict={verdict}
        onStartTimer={startTimer}
        timerStarted={timerStarted}
        startingTimer={startingTimer}
        onReport={
          verdict.rule_id
            ? () => navigation.navigate('ReportSign', { ruleId: verdict.rule_id as string })
            : undefined
        }
      />
      <Button
        title={favoriteSaved ? '★ Saved' : '☆ Save this spot'}
        onPress={saveFavorite}
        disabled={favoriteSaved}
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
    fontWeight: '500',
  },
  note: {
    textAlign: 'center',
  },
});
