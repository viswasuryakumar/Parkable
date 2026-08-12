import React from 'react';
import { StyleSheet, View } from 'react-native';
import Icon, { IconName } from './Icon';
import { useTheme, Theme, RADIUS } from '../theme/colors';

type IconBadgeProps = {
  name: IconName;
  /** One of the theme's *Soft tint keys - defaults to the neutral accent tint. */
  tint?: keyof Theme;
  /** Glyph colour; defaults to the solid counterpart of the tint where obvious. */
  iconColor?: keyof Theme;
  size?: number;
};

/**
 * A soft-tinted circular badge behind an icon - the one recurring visual
 * motif tying screens together.
 *
 * Now draws a real vector icon rather than an emoji: emoji ignore the tint
 * colour entirely, so a badge could never match the state it represented,
 * and they render as a different picture on every platform.
 */
const SOLID_FOR_TINT: Partial<Record<keyof Theme, keyof Theme>> = {
  accentSoft: 'accent',
  parkableSoft: 'parkable',
  notParkableSoft: 'notParkable',
  dependsSoft: 'depends',
  violetSoft: 'violet',
  tealSoft: 'teal',
  roseSoft: 'rose',
};

export default function IconBadge({ name, tint = 'accentSoft', iconColor, size = 56 }: IconBadgeProps) {
  const theme = useTheme();
  const resolved = iconColor ?? SOLID_FOR_TINT[tint] ?? 'text';
  return (
    <View
      style={[
        styles.base,
        { backgroundColor: theme[tint], width: size, height: size, borderRadius: size },
      ]}
    >
      <Icon name={name} size={Math.round(size * 0.46)} color={resolved} />
    </View>
  );
}

const styles = StyleSheet.create({
  base: {
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: RADIUS.pill,
  },
});
