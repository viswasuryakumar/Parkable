import React from 'react';
import { ActivityIndicator, Button, FlatList, Platform, StyleSheet, Text, View } from 'react-native';
import * as Location from 'expo-location';
import { NearbyRule, nearbyParking } from '../services/api';
import { describeLocation } from '../utils/geo';

type ScreenState =
  | { phase: 'loading' }
  | { phase: 'list'; rules: NearbyRule[]; userLat: number; userLng: number }
  | { phase: 'error'; message: string };

// Device reverse-geocoding is native-only (expo-location's web shim always
// throws GeocoderError) - degrade to distance + compass bearing there, which
// needs no external service and works everywhere.
const REVERSE_GEOCODE_SUPPORTED = Platform.OS !== 'web';
const MAX_STREET_LOOKUPS = 15;

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
      setState({
        phase: 'list',
        rules,
        userLat: position.coords.latitude,
        userLng: position.coords.longitude,
      });

      if (REVERSE_GEOCODE_SUPPORTED) {
        // findWithin already orders by distance, so the first N are the
        // ones actually worth a street name.
        for (const rule of rules.slice(0, MAX_STREET_LOOKUPS)) {
          Location.reverseGeocodeAsync({ latitude: rule.lat, longitude: rule.lng })
            .then((results) => {
              const street = results[0]?.street;
              if (street) {
                setStreetNames((prev) => ({ ...prev, [rule.rule_id]: street }));
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

  if (state.rules.length === 0) {
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

  return (
    <View style={styles.listContainer}>
      <Text style={styles.heading}>
        {state.rules.length} rule{state.rules.length === 1 ? '' : 's'} near you
      </Text>
      <FlatList
        data={state.rules}
        keyExtractor={(rule) => rule.rule_id}
        renderItem={({ item }) => {
          const street = streetNames[item.rule_id];
          const location = street
            ? `Near ${street}`
            : describeLocation(userLat, userLng, item.lat, item.lng, item.distance_m);
          return (
            <View style={styles.card}>
              <Text style={styles.cardTitle}>{item.description}</Text>
              <Text style={styles.cardSchedule}>
                {item.days} · {item.hours}
              </Text>
              <Text style={styles.cardLocation}>{location}</Text>
              <Text style={styles.cardMeta}>
                {item.source === 'gov_data' ? 'Official city data' : 'Community sign scan'}
              </Text>
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
  card: {
    backgroundColor: '#f3f4f6',
    borderRadius: 12,
    padding: 14,
    gap: 4,
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
