import React from 'react';
import { ActivityIndicator, Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import * as Location from 'expo-location';
import { CompositeNavigationProp, useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { CheckResult, VerdictResponse, checkParking } from '../services/api';
import VerdictSummary from '../components/VerdictSummary';
import AppButton from '../components/AppButton';
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
  const [signIndex, setSignIndex] = React.useState(0);
  const onScanRequested = React.useCallback(() => navigation.navigate('Scan'), [navigation]);

  const runCheck = React.useCallback(async () => {
    setState({ phase: 'locating' });
    setStreet(null);
    setTimerStarted(false);
    setFavoriteSaved(false);
    setSignIndex(0);
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
      // Best-effort: stay on the same sign if the refreshed result still has
      // that many signs, otherwise fall back to the closest one rather than
      // pointing at an index that no longer exists.
      setSignIndex((prev) => (result.kind === 'multiple_signs' && prev < result.signs.length ? prev : 0));
      setTimerStarted(true);
      const validUntil =
        result.kind === 'verdict'
          ? result.verdict.valid_until
          : result.kind === 'multiple_signs'
            ? result.signs[0]?.valid_until
            : undefined;
      if (validUntil !== undefined) {
        startParkingSession(state.lat, state.lng, validUntil ?? null).catch(() => {});
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
        <AppButton title="Try again" variant="primary" onPress={runCheck} />
      </View>
    );
  }

  if (state.result.kind === 'no_data') {
    return (
      <View style={[styles.container, { backgroundColor: theme.background }]}>
        {street ? <Text style={[styles.location, { color: theme.accent }]}>Near {street}</Text> : null}
        <Text style={[styles.title, { color: theme.text }]}>No parking data here yet</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>{state.result.message}</Text>
        <AppButton title="Scan the sign" variant="primary" onPress={onScanRequested} />
        <AppButton title="Check again" onPress={runCheck} />
      </View>
    );
  }

  // Most of the time /check answers with one sign; when several distinct
  // signs land within the 25m radius it answers with an array instead,
  // rather than silently blending unrelated signs into one verdict (see
  // CheckHandler.groupBySign) - the carousel below is only shown then.
  const signs = state.result.kind === 'multiple_signs' ? state.result.signs : null;
  const activeIndex = signs ? Math.min(signIndex, signs.length - 1) : 0;
  const verdict: VerdictResponse = signs ? signs[activeIndex] : (state.result as { kind: 'verdict'; verdict: VerdictResponse }).verdict;
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
      {signs && signs.length > 1 ? (
        <View style={styles.carouselHeader}>
          <Pressable
            onPress={() => setSignIndex((i) => Math.max(0, i - 1))}
            disabled={activeIndex === 0}
            hitSlop={8}
            style={[
              styles.carouselArrow,
              { backgroundColor: theme.border },
              activeIndex === 0 && styles.carouselArrowDisabled,
            ]}
          >
            <Text style={[styles.carouselArrowText, { color: theme.text }]}>‹</Text>
          </Pressable>
          <Text style={[styles.carouselLabel, { color: theme.textMuted }]}>
            Sign {activeIndex + 1} of {signs.length} nearby
          </Text>
          <Pressable
            onPress={() => setSignIndex((i) => Math.min(signs.length - 1, i + 1))}
            disabled={activeIndex === signs.length - 1}
            hitSlop={8}
            style={[
              styles.carouselArrow,
              { backgroundColor: theme.border },
              activeIndex === signs.length - 1 && styles.carouselArrowDisabled,
            ]}
          >
            <Text style={[styles.carouselArrowText, { color: theme.text }]}>›</Text>
          </Pressable>
        </View>
      ) : null}
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
      <AppButton
        title={favoriteSaved ? '★ Saved' : '☆ Save this spot'}
        onPress={saveFavorite}
        disabled={favoriteSaved}
      />
      <AppButton title="Check again" variant="primary" onPress={runCheck} />
      <AppButton title="Scan a sign instead" onPress={onScanRequested} />
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
  carouselHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
  },
  carouselArrow: {
    width: 28,
    height: 28,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  carouselArrowDisabled: {
    opacity: 0.35,
  },
  carouselArrowText: {
    fontSize: 16,
    fontWeight: '700',
    lineHeight: 18,
  },
  carouselLabel: {
    fontSize: 12,
    fontWeight: '600',
  },
});
