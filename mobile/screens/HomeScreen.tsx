import React from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import type { CompositeNavigationProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTheme, SPACING, RADIUS, TYPE, Theme } from '../theme/colors';
import { getParkingSession, ParkingSession } from '../utils/parkingSession';
import type { RootStackParamList, TabParamList } from '../navigation/types';
import Card from '../components/Card';
import ListRow from '../components/ListRow';
import Icon, { IconName } from '../components/Icon';
import ParkingSessionBanner from '../components/ParkingSessionBanner';

type Navigation = CompositeNavigationProp<
  BottomTabNavigationProp<TabParamList>,
  NativeStackNavigationProp<RootStackParamList>
>;

type Action = {
  label: string;
  hint: string;
  icon: IconName;
  target: keyof TabParamList | 'History' | 'Favorites';
  tint: keyof Theme;
  accent: keyof Theme;
};

/**
 * The two things you actually came to do get big tiles; the three you do
 * occasionally get a compact list. Previously all five were identical tiles,
 * which made "check where I'm standing" look exactly as important as
 * "browse favourites" and left the primary action nowhere in particular.
 */
const PRIMARY_ACTIONS: Action[] = [
  {
    label: 'Check here',
    hint: 'Use my location',
    icon: 'check',
    target: 'Check',
    tint: 'accentSoft',
    accent: 'accent',
  },
  {
    label: 'Scan a sign',
    hint: 'Point your camera',
    icon: 'scan',
    target: 'Scan',
    tint: 'violetSoft',
    accent: 'violet',
  },
];

const SECONDARY_ACTIONS: Action[] = [
  { label: 'Nearby signs', hint: 'What’s on this block', icon: 'nearby', target: 'Nearby', tint: 'tealSoft', accent: 'teal' },
  { label: 'History', hint: 'Past checks and scans', icon: 'history', target: 'History', tint: 'dependsSoft', accent: 'depends' },
  { label: 'Favorites', hint: 'Spots you saved', icon: 'favorites', target: 'Favorites', tint: 'roseSoft', accent: 'rose' },
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
    <ScrollView
      style={{ backgroundColor: theme.background }}
      contentContainerStyle={styles.container}
      showsVerticalScrollIndicator={false}
    >
      <View style={styles.intro}>
        <Text style={[styles.title, { color: theme.text }]}>Can I park here right now?</Text>
        <Text style={[styles.subtitle, { color: theme.textMuted }]}>
          Backed by NYC, SF and Seattle government data, plus every sign the community has scanned.
        </Text>
      </View>

      {session ? (
        <ParkingSessionBanner session={session} onPress={() => navigation.navigate('FindMyCar')} />
      ) : null}

      <View style={styles.primaryGrid}>
        {PRIMARY_ACTIONS.map((action) => (
          <Card
            key={action.target}
            onPress={() => navigation.navigate(action.target)}
            style={styles.tile}
          >
            <View style={[styles.tileIcon, { backgroundColor: theme[action.tint] }]}>
              <Icon name={action.icon} size={24} color={action.accent} />
            </View>
            <View style={styles.tileText}>
              <Text style={[styles.tileLabel, { color: theme.text }]}>{action.label}</Text>
              <Text style={[styles.tileHint, { color: theme.textMuted }]}>{action.hint}</Text>
            </View>
          </Card>
        ))}
      </View>

      <Card flush>
        {SECONDARY_ACTIONS.map((action, index) => (
          <ListRow
            key={action.target}
            icon={action.icon}
            label={action.label}
            sublabel={action.hint}
            tone={action.tint}
            onPress={() => navigation.navigate(action.target)}
            last={index === SECONDARY_ACTIONS.length - 1}
          />
        ))}
      </Card>

      <Pressable style={styles.adminLink} onPress={() => navigation.navigate('Admin')} hitSlop={8}>
        <Text style={[styles.adminLinkText, { color: theme.textSubtle }]}>Review reported signs</Text>
      </Pressable>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    padding: SPACING.lg,
    paddingBottom: SPACING.xxl,
    gap: SPACING.lg,
  },
  intro: {
    gap: SPACING.xs,
    paddingTop: SPACING.xs,
  },
  title: {
    ...TYPE.display,
  },
  subtitle: {
    ...TYPE.body,
  },
  primaryGrid: {
    flexDirection: 'row',
    gap: SPACING.md,
  },
  tile: {
    flex: 1,
    padding: SPACING.lg,
    gap: SPACING.md,
    minHeight: 132,
  },
  tileIcon: {
    width: 44,
    height: 44,
    borderRadius: RADIUS.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  tileText: {
    gap: 2,
  },
  tileLabel: {
    ...TYPE.heading,
  },
  tileHint: {
    ...TYPE.caption,
  },
  adminLink: {
    alignSelf: 'center',
    paddingVertical: SPACING.sm,
  },
  adminLinkText: {
    ...TYPE.caption,
  },
});
