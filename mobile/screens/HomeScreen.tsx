import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import type { CompositeNavigationProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTheme, SPACING, RADIUS, Theme } from '../theme/colors';
import { getParkingSession, ParkingSession } from '../utils/parkingSession';
import type { RootStackParamList, TabParamList } from '../navigation/types';
import Card from '../components/Card';
import IconBadge from '../components/IconBadge';

type Navigation = CompositeNavigationProp<
  BottomTabNavigationProp<TabParamList>,
  NativeStackNavigationProp<RootStackParamList>
>;

type Action = {
  label: string;
  icon: string;
  target: keyof TabParamList | 'History' | 'Favorites';
  tint: keyof Theme;
};

// A distinct tint per tile instead of one flat repeated color - purely
// decorative (violet/teal/rose carry no verdict meaning, unlike
// parkable/notParkable/depends, which stay reserved for actual verdicts).
const QUICK_ACTIONS: Action[] = [
  { label: 'Check here', icon: '🅿️', target: 'Check', tint: 'accentSoft' },
  { label: 'Scan a sign', icon: '📷', target: 'Scan', tint: 'violetSoft' },
  { label: 'Nearby signs', icon: '📍', target: 'Nearby', tint: 'tealSoft' },
  { label: 'History', icon: '🕘', target: 'History', tint: 'dependsSoft' },
  { label: 'Favorites', icon: '⭐', target: 'Favorites', tint: 'roseSoft' },
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
        <Card onPress={() => navigation.navigate('FindMyCar')} style={styles.sessionCard}>
          <IconBadge icon="🚗" tint="accentSoft" size={44} />
          <View style={styles.sessionBody}>
            <Text style={[styles.sessionTitle, { color: theme.text }]}>You have an active parking session</Text>
            <Text style={[styles.sessionNote, { color: theme.textMuted }]}>Tap to find your car</Text>
          </View>
        </Card>
      ) : null}

      <View style={styles.grid}>
        {QUICK_ACTIONS.map((action) => (
          <Card key={action.target} onPress={() => navigation.navigate(action.target)} style={styles.tile}>
            <IconBadge icon={action.icon} tint={action.tint} size={48} />
            <Text style={[styles.tileLabel, { color: theme.text }]}>{action.label}</Text>
          </Card>
        ))}
      </View>

      <Pressable style={styles.adminLink} onPress={() => navigation.navigate('Admin')} hitSlop={8}>
        <Text style={[styles.adminLinkText, { color: theme.textMuted }]}>Review reported signs</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: SPACING.xl,
    gap: SPACING.lg,
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
    gap: SPACING.md,
    padding: SPACING.md,
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
    gap: SPACING.md,
  },
  tile: {
    width: '47%',
    borderRadius: RADIUS.xl,
    padding: SPACING.lg,
    alignItems: 'center',
    gap: SPACING.sm,
  },
  tileLabel: {
    fontSize: 14,
    fontWeight: '600',
    textAlign: 'center',
  },
  adminLink: {
    alignSelf: 'center',
    marginTop: 'auto',
    paddingVertical: SPACING.sm,
  },
  adminLinkText: {
    fontSize: 12,
  },
});
