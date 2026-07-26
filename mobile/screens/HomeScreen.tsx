import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import type { CompositeNavigationProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTheme } from '../theme/colors';
import { getParkingSession, ParkingSession } from '../utils/parkingSession';
import type { RootStackParamList, TabParamList } from '../navigation/types';

type Navigation = CompositeNavigationProp<
  BottomTabNavigationProp<TabParamList>,
  NativeStackNavigationProp<RootStackParamList>
>;

type Action = { label: string; icon: string; target: keyof TabParamList | 'History' | 'Favorites' };

const QUICK_ACTIONS: Action[] = [
  { label: 'Check here', icon: '🅿️', target: 'Check' },
  { label: 'Scan a sign', icon: '📷', target: 'Scan' },
  { label: 'Nearby signs', icon: '📍', target: 'Nearby' },
  { label: 'History', icon: '🕘', target: 'History' },
  { label: 'Favorites', icon: '⭐', target: 'Favorites' },
];

export default function HomeScreen() {
  const theme = useTheme();
  const navigation = useNavigation<Navigation>();
  const [session, setSession] = React.useState<ParkingSession | null>(null);

  useFocusEffect(
    React.useCallback(() => {
      getParkingSession().then(setSession);
    }, [])
  );

  return (
    <View style={[styles.container, { backgroundColor: theme.background }]}>
      <Text style={[styles.title, { color: theme.text }]}>Can I park here right now?</Text>
      <Text style={[styles.subtitle, { color: theme.textMuted }]}>
        Backed by NYC, SF, and Seattle government data, plus every sign the community has scanned.
      </Text>

      {session ? (
        <Pressable
          style={[styles.sessionCard, { backgroundColor: theme.card, borderColor: theme.border }]}
          onPress={() => navigation.navigate('FindMyCar')}
        >
          <Text style={styles.sessionIcon}>🚗</Text>
          <View style={styles.sessionBody}>
            <Text style={[styles.sessionTitle, { color: theme.text }]}>You have an active parking session</Text>
            <Text style={[styles.sessionNote, { color: theme.textMuted }]}>Tap to find your car</Text>
          </View>
        </Pressable>
      ) : null}

      <View style={styles.grid}>
        {QUICK_ACTIONS.map((action) => (
          <Pressable
            key={action.target}
            style={[styles.tile, { backgroundColor: theme.card }]}
            onPress={() => navigation.navigate(action.target)}
          >
            <Text style={styles.tileIcon}>{action.icon}</Text>
            <Text style={[styles.tileLabel, { color: theme.text }]}>{action.label}</Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 20,
    gap: 16,
  },
  title: {
    fontSize: 26,
    fontWeight: '700',
  },
  subtitle: {
    fontSize: 14,
    lineHeight: 20,
  },
  sessionCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    borderRadius: 12,
    borderWidth: 1,
    padding: 14,
  },
  sessionIcon: {
    fontSize: 28,
  },
  sessionBody: {
    flex: 1,
    gap: 2,
  },
  sessionTitle: {
    fontSize: 14,
    fontWeight: '700',
  },
  sessionNote: {
    fontSize: 12,
  },
  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
  },
  tile: {
    width: '47%',
    borderRadius: 14,
    padding: 18,
    alignItems: 'center',
    gap: 8,
  },
  tileIcon: {
    fontSize: 28,
  },
  tileLabel: {
    fontSize: 14,
    fontWeight: '600',
    textAlign: 'center',
  },
});
