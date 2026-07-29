import React from 'react';
import {
  ActivityIndicator,
  Alert,
  FlatList,
  Image,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import * as Location from 'expo-location';
import { NearbyRule, nearbyParking } from '../services/api';
import { describeLocation, metersBetween } from '../utils/geo';
import { useTheme, SPACING } from '../theme/colors';
import NearbyMap from '../components/NearbyMap';
import AppButton from '../components/AppButton';
import { parseDaysLabel, parseStartTime, nextOccurrence } from '../utils/schedule';
import { scheduleNotification } from '../utils/notifications';

// Both platforms have a real map now: react-native-maps on native,
// Leaflet + OpenStreetMap tiles on web (NearbyMap.web.tsx) - different
// underlying libraries (react-native-maps has no web renderer at all), same
// toggle either way.
const MAP_VIEW_SUPPORTED = true;
// Notifications need a dev-client build and don't fire on web at all.
const REMINDERS_SUPPORTED = Platform.OS !== 'web';
const REMINDER_LEAD_MS = 10 * 60 * 60_000; // ~night before a morning restriction

async function remindMeAboutRule(rule: NearbyRule): Promise<void> {
  const dayIndices = parseDaysLabel(rule.days);
  const start = parseStartTime(rule.hours);
  if (!dayIndices || !start) {
    Alert.alert("Can't set a reminder", "This rule's schedule isn't specific enough to remind you about.");
    return;
  }
  const occurrence = nextOccurrence(dayIndices, start.hour, start.minute);
  if (!occurrence) {
    Alert.alert("Can't set a reminder", 'Could not figure out when this rule next applies.');
    return;
  }
  const fireAt = new Date(occurrence.getTime() - REMINDER_LEAD_MS);
  const id = await scheduleNotification(
    'Move your car',
    `${rule.description} starts ${occurrence.toLocaleString()}.`,
    fireAt
  );
  Alert.alert(id ? 'Reminder set' : "Couldn't set reminder", id
    ? `We'll remind you before ${occurrence.toLocaleString()}.`
    : 'Notification permission may be needed - check your device settings.');
}

type ScreenState =
  | { phase: 'loading' }
  | { phase: 'list'; groups: SignGroup[]; userLat: number; userLng: number }
  | { phase: 'error'; message: string };

// Every rule extracted from ONE photo shares that scan's extraction_id
// (ScanHandler stamps the same scan_id on all of it) - a scan's rules are
// panels of ONE physical sign board and always render stacked together.
type ScanGroup = {
  scanId: string;
  rules: NearbyRule[];
};

type SignGroup = {
  key: string;
  lat: number;
  lng: number;
  distanceM: number;
  source: string;
  // Almost always one entry. More than one means two DIFFERENT scans landed
  // at (near enough) the same point - a real, distinct second sign a few
  // metres away, not a re-read of the first (the backend's content-match
  // supersede already collapses true re-reads into one scan_id) - rendered
  // as a horizontal carousel instead of stacking unrelated signs into one
  // card, which would read as one sign with contradictory rules.
  scans: ScanGroup[];
};

// Device reverse-geocoding is native-only (expo-location's web shim always
// throws GeocoderError) - degrade to distance + compass bearing there, which
// needs no external service and works everywhere.
const REVERSE_GEOCODE_SUPPORTED = Platform.OS !== 'web';
const MAX_STREET_LOOKUPS = 15;

// A phone's GPS is routinely 5-15m off, and two scans of the very same
// physical sign taken from slightly different standing positions land at
// measurably different raw coordinates - exact-match grouping (the previous
// approach, rounding to ~1m) split real re-reads of one sign into separate
// "signs" in the list the moment GPS drift exceeded that (found live: a
// sign's two rules, originally shown together, later appeared as two
// separate single-rule signs after a re-scan). Clustering by proximity
// instead keeps them together; content/scan_id (handled separately, below)
// is still what decides whether nearby entries are truly distinct signs.
const SIGN_CLUSTER_RADIUS_METERS = 15;

/**
 * Every rule extracted from ONE photo shares that scan's single GPS reading
 * exactly, and gov-data rows likewise carry one real-world point per
 * regulation - so clustering by proximity recovers "which rules came from
 * signs standing at the same spot" with no extra lookup. Within that,
 * scan_id separates "panels of one sign" from "two different signs that
 * happen to share a spot."
 */
function groupBySign(rules: NearbyRule[]): SignGroup[] {
  const groups: SignGroup[] = [];
  for (const rule of rules) {
    let group = groups.find(
      (g) => g.source === rule.source && metersBetween(g.lat, g.lng, rule.lat, rule.lng) <= SIGN_CLUSTER_RADIUS_METERS
    );
    if (!group) {
      group = {
        key: `${rule.source}:${rule.lat.toFixed(5)}:${rule.lng.toFixed(5)}`,
        lat: rule.lat,
        lng: rule.lng,
        distanceM: rule.distance_m,
        source: rule.source,
        scans: [],
      };
      groups.push(group);
    }
    let scan = group.scans.find((s) => s.scanId === rule.scan_id);
    if (!scan) {
      scan = { scanId: rule.scan_id, rules: [] };
      group.scans.push(scan);
    }
    scan.rules.push(rule);
  }
  return groups.sort((a, b) => a.distanceM - b.distanceM);
}

export default function NearbyScreen() {
  const theme = useTheme();
  const [state, setState] = React.useState<ScreenState>({ phase: 'loading' });
  const [streetNames, setStreetNames] = React.useState<Record<string, string>>({});
  const [activeScanIndex, setActiveScanIndex] = React.useState<Record<string, number>>({});
  const [viewMode, setViewMode] = React.useState<'list' | 'map'>('list');

  const load = React.useCallback(async () => {
    setState({ phase: 'loading' });
    setStreetNames({});
    setActiveScanIndex({});
    try {
      const permission = await Location.requestForegroundPermissionsAsync();
      if (permission.status !== 'granted') {
        setState({ phase: 'error', message: 'Location permission is required to list nearby rules.' });
        return;
      }
      const position = await Location.getCurrentPositionAsync({
        accuracy: Location.Accuracy.Balanced,
      });
      const rules = await nearbyParking(position.coords.latitude, position.coords.longitude);
      const groups = groupBySign(rules);
      setState({
        phase: 'list',
        groups,
        userLat: position.coords.latitude,
        userLng: position.coords.longitude,
      });

      if (REVERSE_GEOCODE_SUPPORTED) {
        // Already nearest-first, and one lookup now covers a whole group.
        for (const group of groups.slice(0, MAX_STREET_LOOKUPS)) {
          Location.reverseGeocodeAsync({ latitude: group.lat, longitude: group.lng })
            .then((results) => {
              const street = results[0]?.street;
              if (street) {
                setStreetNames((prev) => ({ ...prev, [group.key]: street }));
              }
            })
            .catch(() => {
              // Honest degrade: the distance + bearing line already covers
              // "where", so a failed lookup is not worth surfacing as an error.
            });
        }
      }
    } catch (e) {
      setState({ phase: 'error', message: e instanceof Error ? e.message : 'Unknown error' });
    }
  }, []);

  React.useEffect(() => {
    load();
  }, [load]);

  if (state.phase === 'loading') {
    return (
      <View style={[styles.centered, { backgroundColor: theme.background }]}>
        <ActivityIndicator size="large" />
        <Text style={[styles.title, { color: theme.text }]}>Finding rules near you…</Text>
      </View>
    );
  }

  if (state.phase === 'error') {
    return (
      <View style={[styles.centered, { backgroundColor: theme.background }]}>
        <Text style={[styles.title, { color: theme.text }]}>Something went wrong</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>{state.message}</Text>
        <AppButton title="Try again" variant="primary" onPress={load} />
      </View>
    );
  }

  if (state.groups.length === 0) {
    return (
      <View style={[styles.centered, { backgroundColor: theme.background }]}>
        <Text style={[styles.title, { color: theme.text }]}>No rules nearby</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>
          No parking rules are recorded within a kilometre of you yet. Scan a sign to add the
          first one.
        </Text>
        <AppButton title="Refresh" variant="primary" onPress={load} />
      </View>
    );
  }

  const { userLat, userLng } = state;
  const totalRules = state.groups.reduce(
    (sum, g) => sum + g.scans.reduce((scanSum, s) => scanSum + s.rules.length, 0),
    0
  );
  const totalSigns = state.groups.reduce((sum, g) => sum + g.scans.length, 0);

  return (
    <View style={[styles.listContainer, { backgroundColor: theme.background }]}>
      <View style={styles.headingRow}>
        <Text style={[styles.heading, { color: theme.text }]}>
          {totalRules} rule{totalRules === 1 ? '' : 's'} on {totalSigns} sign
          {totalSigns === 1 ? '' : 's'} near you
        </Text>
        {MAP_VIEW_SUPPORTED ? (
          <Pressable
            onPress={() => setViewMode((prev) => (prev === 'list' ? 'map' : 'list'))}
            style={[styles.viewToggle, { backgroundColor: theme.accentSoft }]}
          >
            <Text style={[styles.viewToggleText, { color: theme.accent }]}>
              {viewMode === 'list' ? '🗺️ Map' : '☰ List'}
            </Text>
          </Pressable>
        ) : null}
      </View>
      {viewMode === 'map' ? (
        <NearbyMap
          userLat={userLat}
          userLng={userLng}
          // One marker per SCAN, not per location cluster - each scan has
          // its own real reported coordinates, and a cluster can hold
          // several genuinely distinct scans (found live: "3 signs" all
          // showed as a single pin, because the cluster's own reference
          // point was reused for every scan inside it instead of each
          // scan's actual GPS reading).
          groups={state.groups.flatMap((group) =>
            group.scans.map((scan) => ({
              key: `${group.key}:${scan.scanId}`,
              lat: scan.rules[0]?.lat ?? group.lat,
              lng: scan.rules[0]?.lng ?? group.lng,
              description: scan.rules.map((rule) => rule.description).join(' · '),
            }))
          )}
        />
      ) : (
      <FlatList
        data={state.groups}
        keyExtractor={(group) => group.key}
        renderItem={({ item: group }) => {
          const street = streetNames[group.key];
          const location = street
            ? `Near ${street}`
            : describeLocation(userLat, userLng, group.lat, group.lng, group.distanceM);
          const scanIndex = Math.min(activeScanIndex[group.key] ?? 0, group.scans.length - 1);
          const activeScan = group.scans[scanIndex];
          const hasMultipleSigns = group.scans.length > 1;
          const moveScan = (delta: number) =>
            setActiveScanIndex((prev) => ({
              ...prev,
              [group.key]: Math.max(0, Math.min(group.scans.length - 1, scanIndex + delta)),
            }));
          return (
            <View style={[styles.signCard, { backgroundColor: theme.card, shadowColor: theme.text }]}>
              <View style={[styles.signPost, { backgroundColor: theme.textMuted }]} />
              <View style={styles.signBody}>
                <Text style={[styles.cardLocation, { color: theme.accent }]}>{location}</Text>
                {hasMultipleSigns && (
                  <View style={styles.carouselHeader}>
                    <Pressable
                      onPress={() => moveScan(-1)}
                      disabled={scanIndex === 0}
                      hitSlop={8}
                      style={[
                        styles.carouselArrow,
                        { backgroundColor: theme.border },
                        scanIndex === 0 && styles.carouselArrowDisabled,
                      ]}
                    >
                      <Text style={[styles.carouselArrowText, { color: theme.text }]}>‹</Text>
                    </Pressable>
                    <Text style={[styles.carouselLabel, { color: theme.textMuted }]}>
                      Sign {scanIndex + 1} of {group.scans.length} here
                    </Text>
                    <Pressable
                      onPress={() => moveScan(1)}
                      disabled={scanIndex === group.scans.length - 1}
                      hitSlop={8}
                      style={[
                        styles.carouselArrow,
                        { backgroundColor: theme.border },
                        scanIndex === group.scans.length - 1 && styles.carouselArrowDisabled,
                      ]}
                    >
                      <Text style={[styles.carouselArrowText, { color: theme.text }]}>›</Text>
                    </Pressable>
                  </View>
                )}
                {activeScan.rules[0]?.photo_url ? (
                  <Image
                    source={{ uri: activeScan.rules[0].photo_url }}
                    style={[styles.signPhoto, { backgroundColor: theme.border }]}
                    resizeMode="contain"
                  />
                ) : null}
                {activeScan.rules.map((rule, index) => (
                  <View
                    key={rule.rule_id}
                    style={[
                      index > 0 && styles.rulePanelDivider,
                      index > 0 && { borderTopColor: theme.border },
                    ]}
                  >
                    <Text style={[styles.cardTitle, { color: theme.text }]}>{rule.description}</Text>
                    <Text style={[styles.cardSchedule, { color: theme.textMuted }]}>
                      {rule.days} · {rule.hours}
                    </Text>
                    {REMINDERS_SUPPORTED && parseDaysLabel(rule.days) && parseStartTime(rule.hours) ? (
                      <Pressable onPress={() => remindMeAboutRule(rule)} hitSlop={8}>
                        <Text style={[styles.reminderLink, { color: theme.accent }]}>🔔 Remind me</Text>
                      </Pressable>
                    ) : null}
                  </View>
                ))}
                <Text style={[styles.cardMeta, { color: theme.textMuted }]}>
                  {group.source === 'gov_data' ? 'Official city data' : 'Community sign scan'}
                </Text>
              </View>
            </View>
          );
        }}
        contentContainerStyle={styles.listContent}
      />
      )}
      <AppButton title="Refresh" onPress={load} />
    </View>
  );
}

const styles = StyleSheet.create({
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
    gap: 12,
  },
  listContainer: {
    flex: 1,
    padding: 16,
    gap: 12,
  },
  headingRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  heading: {
    fontSize: 20,
    fontWeight: '600',
    flexShrink: 1,
  },
  viewToggle: {
    borderRadius: 999,
    paddingVertical: SPACING.xs + 2,
    paddingHorizontal: SPACING.md,
  },
  viewToggleText: {
    fontSize: 13,
    fontWeight: '700',
  },
  listContent: {
    gap: 8,
  },
  // A single visual "post" strip down the left edge, tying every panel on
  // this card to one physical sign board rather than reading as unrelated
  // separate entries.
  signCard: {
    flexDirection: 'row',
    borderRadius: 12,
    overflow: 'hidden',
    ...Platform.select({
      web: { boxShadow: '0 1px 3px rgba(0,0,0,0.08), 0 1px 2px rgba(0,0,0,0.06)' },
      android: { elevation: 2 },
      default: { shadowOffset: { width: 0, height: 1 }, shadowOpacity: 0.08, shadowRadius: 3 },
    }),
  },
  carouselHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 2,
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
  signPost: {
    width: 6,
  },
  signBody: {
    flex: 1,
    padding: 14,
    gap: 6,
  },
  signPhoto: {
    width: '100%',
    height: 140,
    borderRadius: 8,
  },
  rulePanelDivider: {
    borderTopWidth: 1,
    marginTop: 6,
    paddingTop: 6,
  },
  cardTitle: {
    fontSize: 15,
    fontWeight: '600',
  },
  cardSchedule: {
    fontSize: 13,
  },
  reminderLink: {
    fontSize: 12,
    fontWeight: '600',
    marginTop: 4,
  },
  cardLocation: {
    fontSize: 13,
    fontWeight: '500',
  },
  cardMeta: {
    fontSize: 12,
    marginTop: 4,
  },
  title: {
    fontSize: 24,
    fontWeight: '600',
    textAlign: 'center',
  },
  note: {
    textAlign: 'center',
  },
});
