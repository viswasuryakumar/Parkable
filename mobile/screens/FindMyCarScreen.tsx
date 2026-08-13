import React from 'react';
import { StyleSheet, Text, View, ViewStyle } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import * as Location from 'expo-location';
import { ParkingSession, clearParkingSession, getParkingSession } from '../utils/parkingSession';
import { bearingLabel, formatDistance, metersBetween } from '../utils/geo';
import { useTheme, SPACING, RADIUS, TYPE, ELEVATION } from '../theme/colors';
import { formatDuration, useCountdown } from '../utils/countdown';
import IconBadge from '../components/IconBadge';
import AppButton from '../components/AppButton';
import ScreenContainer from '../components/ScreenContainer';
import ParkingMap from '../components/ParkingMap';
import { fitFocus } from '../components/ParkingMap.types';

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
 * check time - see utils/parkingSession.ts).
 *
 * Shown on a real map rather than as a line of text: "82m Northeast of you"
 * is precise but not actually usable while walking - it can't tell you which
 * side of the block you're on, and it says nothing at all once you're within
 * the GPS noise floor ("Right where you're standing" was the entire answer).
 * The map keeps that sentence as the summary and puts the pin somewhere you
 * can navigate by.
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
    return <ScreenContainer>{null}</ScreenContainer>;
  }

  if (session === null) {
    return (
      <ScreenContainer>
        <IconBadge name="car" />
        <Text style={[styles.title, { color: theme.text }]}>No active parking session</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>
          Start a parking timer from the Check or Scan tab to track where you left your car.
        </Text>
      </ScreenContainer>
    );
  }

  const car = { lat: session.lat, lng: session.lng };
  const distanceM = here ? metersBetween(here.lat, here.lng, car.lat, car.lng) : null;
  // Direction as words, distance as its own chip - describeLocation()'s single
  // sentence would repeat the number the chip already shows.
  const bearing =
    here && distanceM !== null ? bearingLabel(here.lat, here.lng, car.lat, car.lng, distanceM) : null;
  const direction = !here
    ? 'Finding your location…'
    : bearing
      ? `${bearing} of you`
      : "Right where you're standing";

  return (
    <View style={[styles.screen, { backgroundColor: theme.background }]}>
      <View style={styles.mapArea}>
        <ParkingMap
          markers={[
            { key: 'car', lat: car.lat, lng: car.lng, description: 'Your car is parked here', variant: 'car' },
          ]}
          user={here}
          // Frame you and the car together once there's a fix to frame; until
          // then the car alone is the honest view.
          focus={fitFocus(here ? [car, here] : [car])}
        />
      </View>
      <View
        style={[
          styles.sheet,
          { backgroundColor: theme.card, borderColor: theme.border },
          ELEVATION.raised as ViewStyle,
        ]}
      >
        <View style={styles.sheetHead}>
          <IconBadge name="car" tint="parkableSoft" size={44} />
          <View style={styles.sheetHeadText}>
            <Text style={[styles.sheetTitle, { color: theme.text }]}>Your car is here</Text>
            <Text style={[styles.location, { color: theme.accent }]}>{direction}</Text>
          </View>
          {distanceM !== null ? (
            <View style={[styles.distanceChip, { backgroundColor: theme.surfaceMuted }]}>
              <Text style={[styles.distanceText, { color: theme.textMuted }]}>
                {formatDistance(distanceM)}
              </Text>
            </View>
          ) : null}
        </View>
        <RemainingLine validUntil={session.validUntil} />
        <AppButton title="I've moved my car" variant="primary" fullWidth onPress={handleClear} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
  },
  mapArea: {
    flex: 1,
  },
  // Floats over the map rather than sitting below it, so the map keeps the
  // full height of the screen behind the summary.
  sheet: {
    position: 'absolute',
    left: SPACING.lg,
    right: SPACING.lg,
    bottom: SPACING.lg,
    padding: SPACING.lg,
    borderRadius: RADIUS.lg,
    borderWidth: StyleSheet.hairlineWidth,
    gap: SPACING.md,
    // Leaflet's own panes carry z-indexes of their own on web; without this
    // the sheet can end up painted underneath the tiles.
    zIndex: 10,
  },
  sheetHead: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.md,
  },
  sheetHeadText: {
    flex: 1,
    gap: 2,
  },
  title: {
    ...TYPE.title,
    textAlign: 'center',
  },
  sheetTitle: {
    ...TYPE.heading,
  },
  location: {
    ...TYPE.label,
  },
  distanceChip: {
    paddingHorizontal: SPACING.md,
    paddingVertical: 6,
    borderRadius: RADIUS.pill,
  },
  distanceText: {
    ...TYPE.label,
  },
  note: {
    ...TYPE.body,
    textAlign: 'center',
  },
});
