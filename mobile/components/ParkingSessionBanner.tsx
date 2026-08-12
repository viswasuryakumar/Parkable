import React from 'react';
import { Platform, Pressable, StyleSheet, Text, View, ViewStyle } from 'react-native';
import Icon from './Icon';
import { useTheme, SPACING, RADIUS, TYPE, Theme } from '../theme/colors';
import { ParkingSession } from '../utils/parkingSession';
import { formatDuration, urgencyOf, useCountdown, useElapsed, Urgency } from '../utils/countdown';

type Props = {
  session: ParkingSession;
  onPress: () => void;
};

/**
 * The active-parking callout on Home.
 *
 * Deliberately NOT built on <Card>: sharing that surface gave it the same
 * grey `theme.card` fill and the same accentSoft icon tint as the "Check
 * here" tile directly beneath it, so the one time-critical thing on the
 * screen read as just another menu item. This carries its own tinted fill
 * and a 2px colored border so it separates from the tile grid at a glance.
 *
 * Colour tracks urgency rather than being decorative. Using the verdict
 * palette here is intentional and consistent with theme/colors.ts reserving
 * it for "actual verdicts": an expired meter *is* a NOT_PARKABLE state for
 * the spot you're standing in, not a decorative accent.
 */
const URGENCY_COLORS: Record<Urgency, { strong: keyof Theme; soft: keyof Theme }> = {
  normal: { strong: 'accent', soft: 'accentSoft' },
  soon: { strong: 'depends', soft: 'dependsSoft' },
  urgent: { strong: 'notParkable', soft: 'notParkableSoft' },
  expired: { strong: 'notParkable', soft: 'notParkableSoft' },
};

const URGENCY_LABEL: Record<Urgency, string> = {
  normal: 'Time left on your parking',
  soon: 'Your parking runs out soon',
  urgent: 'Move your car soon',
  expired: 'Your parking time is up',
};

export default function ParkingSessionBanner({ session, onPress }: Props) {
  const theme = useTheme();
  const msLeft = useCountdown(session.validUntil);
  const elapsedMs = useElapsed(session.startedAt);

  // An untimed session (a PARKABLE with no limit) has nothing to count down
  // to, so show time parked instead - still a live clock, just measuring the
  // other direction, rather than an empty space where the timer should be.
  const isTimed = msLeft !== null;
  const urgency: Urgency = isTimed ? urgencyOf(msLeft) : 'normal';
  const palette = URGENCY_COLORS[urgency];
  const strong = theme[palette.strong];
  const soft = theme[palette.soft];

  const clock = formatDuration(isTimed ? msLeft : elapsedMs);
  const heading = isTimed ? URGENCY_LABEL[urgency] : 'You are parked here';
  const caption = isTimed
    ? urgency === 'expired'
      ? 'Tap to find your car'
      : 'left · tap to find your car'
    : 'parked · tap to find your car';

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${heading}. ${clock} ${isTimed ? 'remaining' : 'parked'}. Tap to find your car.`}
      onPress={onPress}
      style={({ pressed }) =>
        [styles.banner, { backgroundColor: soft, borderColor: strong }, pressed && styles.pressed] as ViewStyle[]
      }
    >
      <View style={styles.headingRow}>
        <Icon
          name={urgency === 'expired' || urgency === 'urgent' ? 'bell' : 'car'}
          size={20}
          tint={strong}
        />
        <Text style={[styles.heading, { color: strong }]} numberOfLines={2}>
          {heading}
        </Text>
      </View>

      <View style={styles.clockRow}>
        <Text style={[styles.clock, { color: strong }]}>{clock}</Text>
        <Text style={[styles.caption, { color: theme.textMuted }]} numberOfLines={2}>
          {caption}
        </Text>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  banner: {
    borderWidth: 2,
    borderRadius: RADIUS.xl,
    padding: SPACING.lg,
    gap: SPACING.sm,
  },
  pressed: {
    opacity: 0.85,
  },
  headingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.sm,
  },
  heading: {
    flex: 1,
    ...TYPE.bodyStrong,
    fontWeight: '700',
  },
  clockRow: {
    flexDirection: 'row',
    alignItems: 'baseline',
    gap: SPACING.sm,
  },
  clock: {
    fontSize: 40,
    fontWeight: '800',
    // Without tabular figures the digits change width as they tick, so the
    // whole line jitters left and right once a second.
    ...Platform.select({
      android: { fontFamily: 'monospace' },
      default: { fontVariant: ['tabular-nums'] as const },
    }),
  },
  caption: {
    flex: 1,
    fontSize: 12,
  },
});
