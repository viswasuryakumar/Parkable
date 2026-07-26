import React from 'react';
import {
  ActivityIndicator,
  Button,
  FlatList,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import * as Location from 'expo-location';
import { NearbyRule, nearbyParking } from '../services/api';
import { describeLocation } from '../utils/geo';
import { useTheme } from '../theme/colors';

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

/**
 * Every rule extracted from ONE photo shares that scan's single GPS reading
 * exactly, and gov-data rows likewise carry one real-world point per
 * regulation - so rounding to ~1m and grouping by (source, point) recovers
 * "which rules came from signs standing at the same spot" with no extra
 * lookup. Within that, scan_id separates "panels of one sign" from
 * "two different signs that happen to share a spot."
 */
function groupBySign(rules: NearbyRule[]): SignGroup[] {
  const groups = new Map<string, SignGroup>();
  for (const rule of rules) {
    const key = `${rule.source}:${rule.lat.toFixed(5)}:${rule.lng.toFixed(5)}`;
    let group = groups.get(key);
    if (!group) {
      group = {
        key,
        lat: rule.lat,
        lng: rule.lng,
        distanceM: rule.distance_m,
        source: rule.source,
        scans: [],
      };
      groups.set(key, group);
    }
    let scan = group.scans.find((s) => s.scanId === rule.scan_id);
    if (!scan) {
      scan = { scanId: rule.scan_id, rules: [] };
      group.scans.push(scan);
    }
    scan.rules.push(rule);
  }
  return Array.from(groups.values()).sort((a, b) => a.distanceM - b.distanceM);
}

export default function NearbyScreen() {
  const theme = useTheme();
  const [state, setState] = React.useState<ScreenState>({ phase: 'loading' });
  const [streetNames, setStreetNames] = React.useState<Record<string, string>>({});
  const [activeScanIndex, setActiveScanIndex] = React.useState<Record<string, number>>({});

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
        <Button title="Try again" onPress={load} />
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
        <Button title="Refresh" onPress={load} />
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
      <Text style={[styles.heading, { color: theme.text }]}>
        {totalRules} rule{totalRules === 1 ? '' : 's'} on {totalSigns} sign
        {totalSigns === 1 ? '' : 's'} near you
      </Text>
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
            <View style={[styles.signCard, { backgroundColor: theme.card }]}>
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
      <Button title="Refresh" onPress={load} />
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
  heading: {
    fontSize: 20,
    fontWeight: '600',
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
