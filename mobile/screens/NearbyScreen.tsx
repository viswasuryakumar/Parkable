import React from 'react';
import { ActivityIndicator, Button, FlatList, Platform, StyleSheet, Text, View } from 'react-native';
import * as Location from 'expo-location';
import { NearbyRule, nearbyParking } from '../services/api';
import { describeLocation } from '../utils/geo';

type ScreenState =
  | { phase: 'loading' }
  | { phase: 'list'; groups: SignGroup[]; userLat: number; userLng: number }
  | { phase: 'error'; message: string };

type SignGroup = {
  key: string;
  lat: number;
  lng: number;
  distanceM: number;
  source: string;
  rules: NearbyRule[];
};

// Device reverse-geocoding is native-only (expo-location's web shim always
// throws GeocoderError) - degrade to distance + compass bearing there, which
// needs no external service and works everywhere.
const REVERSE_GEOCODE_SUPPORTED = Platform.OS !== 'web';
const MAX_STREET_LOOKUPS = 15;

/**
 * Every rule extracted from ONE photo shares that scan's single GPS reading
 * exactly (ScanHandler stamps the same lat/lng on all of it), and gov-data
 * rows likewise carry one real-world point per regulation - so rounding
 * to ~1m and grouping by (source, point) recovers "which rules came off the
 * same physical sign post" with no backend change or extra field needed.
 */
function groupBySign(rules: NearbyRule[]): SignGroup[] {
  const groups = new Map<string, SignGroup>();
  for (const rule of rules) {
    const key = `${rule.source}:${rule.lat.toFixed(5)}:${rule.lng.toFixed(5)}`;
    const existing = groups.get(key);
    if (existing) {
      existing.rules.push(rule);
    } else {
      groups.set(key, {
        key,
        lat: rule.lat,
        lng: rule.lng,
        distanceM: rule.distance_m,
        source: rule.source,
        rules: [rule],
      });
    }
  }
  return Array.from(groups.values()).sort((a, b) => a.distanceM - b.distanceM);
}

export default function NearbyScreen() {
  const [state, setState] = React.useState<ScreenState>({ phase: 'loading' });
  const [streetNames, setStreetNames] = React.useState<Record<string, string>>({});

  const load = React.useCallback(async () => {
    setState({ phase: 'loading' });
    setStreetNames({});
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
      <View style={styles.centered}>
        <ActivityIndicator size="large" />
        <Text style={styles.title}>Finding rules near you…</Text>
      </View>
    );
  }

  if (state.phase === 'error') {
    return (
      <View style={styles.centered}>
        <Text style={styles.title}>Something went wrong</Text>
        <Text style={styles.note}>{state.message}</Text>
        <Button title="Try again" onPress={load} />
      </View>
    );
  }

  if (state.groups.length === 0) {
    return (
      <View style={styles.centered}>
        <Text style={styles.title}>No rules nearby</Text>
        <Text style={styles.note}>
          No parking rules are recorded within a kilometre of you yet. Scan a sign to add the
          first one.
        </Text>
        <Button title="Refresh" onPress={load} />
      </View>
    );
  }

  const { userLat, userLng } = state;
  const totalRules = state.groups.reduce((sum, g) => sum + g.rules.length, 0);

  return (
    <View style={styles.listContainer}>
      <Text style={styles.heading}>
        {totalRules} rule{totalRules === 1 ? '' : 's'} on {state.groups.length} sign
        {state.groups.length === 1 ? '' : 's'} near you
      </Text>
      <FlatList
        data={state.groups}
        keyExtractor={(group) => group.key}
        renderItem={({ item: group }) => {
          const street = streetNames[group.key];
          const location = street
            ? `Near ${street}`
            : describeLocation(userLat, userLng, group.lat, group.lng, group.distanceM);
          return (
            <View style={styles.signCard}>
              <View style={styles.signPost} />
              <View style={styles.signBody}>
                <Text style={styles.cardLocation}>{location}</Text>
                {group.rules.map((rule, index) => (
                  <View
                    key={rule.rule_id}
                    style={index > 0 ? styles.rulePanelDivider : undefined}
                  >
                    <Text style={styles.cardTitle}>{rule.description}</Text>
                    <Text style={styles.cardSchedule}>
                      {rule.days} · {rule.hours}
                    </Text>
                  </View>
                ))}
                <Text style={styles.cardMeta}>
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
    backgroundColor: '#f3f4f6',
    borderRadius: 12,
    overflow: 'hidden',
  },
  signPost: {
    width: 6,
    backgroundColor: '#9ca3af',
  },
  signBody: {
    flex: 1,
    padding: 14,
    gap: 6,
  },
  rulePanelDivider: {
    borderTopWidth: 1,
    borderTopColor: '#d1d5db',
    marginTop: 6,
    paddingTop: 6,
  },
  cardTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: '#111827',
  },
  cardSchedule: {
    fontSize: 13,
    color: '#374151',
  },
  cardLocation: {
    fontSize: 13,
    color: '#2563eb',
    fontWeight: '500',
  },
  cardMeta: {
    fontSize: 12,
    color: '#6b7280',
    marginTop: 4,
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
