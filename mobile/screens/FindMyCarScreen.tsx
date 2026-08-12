import React from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import * as Location from 'expo-location';
import { ParkingSession, clearParkingSession, getParkingSession } from '../utils/parkingSession';
import { describeLocation, metersBetween } from '../utils/geo';
import { useTheme, SPACING } from '../theme/colors';
import { formatDuration, useCountdown } from '../utils/countdown';
import IconBadge from '../components/IconBadge';
import AppButton from '../components/AppButton';

/**
 * The live "time left" line. Split out as its own component because hooks
 * can't run conditionally, and this screen returns early for the no-session
 * and still-loading states before it knows there's a deadline to count.
 */
function RemainingLine({ validUntil }: { validUntil: string | null }) {
  const theme = useTheme();
  const msLeft = useCountdown(validUntil);
  if (msLeft === null) {
    return null;
  }
  const expired = msLeft <= 0;
  const color = expired ? theme.notParkable : theme.textMuted;
  return (
    <Text style={[styles.note, { color }]}>
      {expired ? 'Your time is up' : `${formatDuration(msLeft)} left`}
    </Text>
  );
}

/**
 * Where you parked, saved the moment a parking timer starts (not at scan/
 * check time - see utils/parkingSession.ts). Purely a distance + compass
 * bearing back to that point, same client-only approach NearbyScreen
 * already uses for its "no map on web" degrade - no new map dependency
 * needed just for a single pin.
 */
export default function FindMyCarScreen() {
  const theme = useTheme();
  const [session, setSession] = React.useState<ParkingSession | null | undefined>(undefined);
  const [here, setHere] = React.useState<{ lat: number; lng: number } | null>(null);

  const load = React.useCallback(() => {
    getParkingSession().then(setSession);
    Location.requestForegroundPermissionsAsync().then((permission) => {
      if (permission.status !== 'granted') {
        return;
      }
      Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced }).then((position) =>
        setHere({ lat: position.coords.latitude, lng: position.coords.longitude })
      );
    });
  }, []);

  useFocusEffect(load);

  async function handleClear() {
    await clearParkingSession();
    setSession(null);
  }

  if (session === undefined) {
    return <View style={[styles.centered, { backgroundColor: theme.background }]} />;
  }

  if (session === null) {
    return (
      <View style={[styles.centered, { backgroundColor: theme.background }]}>
        <IconBadge name="car" />
        <Text style={[styles.title, { color: theme.text }]}>No active parking session</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>
          Start a parking timer from the Check or Scan tab to track where you left your car.
        </Text>
      </View>
    );
  }

  const location = here
    ? describeLocation(here.lat, here.lng, session.lat, session.lng, metersBetween(here.lat, here.lng, session.lat, session.lng))
    : null;

  return (
    <View style={[styles.centered, { backgroundColor: theme.background }]}>
      <IconBadge name="pin" tint="parkableSoft" size={72} />
      <Text style={[styles.title, { color: theme.text }]}>Your car is here</Text>
      {location ? (
        <Text style={[styles.location, { color: theme.accent }]}>{location}</Text>
      ) : (
        <ActivityIndicator />
      )}
      <RemainingLine validUntil={session.validUntil} />
      <AppButton title="I've moved my car" variant="primary" onPress={handleClear} />
    </View>
  );
}

const styles = StyleSheet.create({
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: SPACING.xl,
    gap: SPACING.md,
  },
  title: {
    fontSize: 22,
    fontWeight: '700',
    textAlign: 'center',
  },
  location: {
    fontSize: 16,
    fontWeight: '500',
  },
  note: {
    textAlign: 'center',
  },
});
