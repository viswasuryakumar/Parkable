import React from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import * as Location from 'expo-location';
import { ParkingSession, clearParkingSession, getParkingSession } from '../utils/parkingSession';
import { describeLocation, metersBetween } from '../utils/geo';
import { useTheme, SPACING } from '../theme/colors';
import IconBadge from '../components/IconBadge';
import AppButton from '../components/AppButton';

function formatRemaining(validUntil: string | null): string | null {
  if (!validUntil) {
    return null;
  }
  const msRemaining = Date.parse(validUntil) - Date.now();
  if (msRemaining <= 0) {
    return 'Your time may already be up';
  }
  const minutes = Math.round(msRemaining / 60_000);
  if (minutes < 60) {
    return `${minutes}m left`;
  }
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m left`;
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
        <IconBadge icon="🚗" />
        <Text style={[styles.title, { color: theme.text }]}>No active parking session</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>
          Start a parking timer from the Check or Scan tab to track where you left your car.
        </Text>
      </View>
    );
  }

  const remaining = formatRemaining(session.validUntil);
  const location = here
    ? describeLocation(here.lat, here.lng, session.lat, session.lng, metersBetween(here.lat, here.lng, session.lat, session.lng))
    : null;

  return (
    <View style={[styles.centered, { backgroundColor: theme.background }]}>
      <IconBadge icon="📍" tint="parkableSoft" size={72} />
      <Text style={[styles.title, { color: theme.text }]}>Your car is here</Text>
      {location ? (
        <Text style={[styles.location, { color: theme.accent }]}>{location}</Text>
      ) : (
        <ActivityIndicator />
      )}
      {remaining ? <Text style={[styles.note, { color: theme.textMuted }]}>{remaining}</Text> : null}
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
