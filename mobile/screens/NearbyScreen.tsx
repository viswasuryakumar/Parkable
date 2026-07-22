import React from 'react';
import { ActivityIndicator, Button, FlatList, StyleSheet, Text, View } from 'react-native';
import * as Location from 'expo-location';
import { NearbyRule, nearbyParking } from '../services/api';

type ScreenState =
  | { phase: 'loading' }
  | { phase: 'list'; rules: NearbyRule[] }
  | { phase: 'error'; message: string };

export default function NearbyScreen() {
  const [state, setState] = React.useState<ScreenState>({ phase: 'loading' });

  const load = React.useCallback(async () => {
    setState({ phase: 'loading' });
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
      setState({ phase: 'list', rules });
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

  return (
    <View style={styles.listContainer}>
      <Text style={styles.heading}>
        {state.rules.length} rule{state.rules.length === 1 ? '' : 's'} near you
      </Text>
      <FlatList
        data={state.rules}
        keyExtractor={(rule) => rule.rule_id}
        renderItem={({ item }) => (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>{item.description}</Text>
            <Text style={styles.cardMeta}>
              {item.source === 'gov_data' ? 'Official city data' : 'Community sign scan'}
            </Text>
          </View>
        )}
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
