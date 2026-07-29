import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useTheme, Theme, RADIUS } from '../theme/colors';

type IconBadgeProps = {
  icon: string;
  /** One of the theme's *Soft tint keys - defaults to the neutral accent tint. */
  tint?: keyof Theme;
  size?: number;
};

/**
 * A soft-tinted circular badge behind an emoji - the one recurring visual
 * motif tying screens together, replacing emoji that previously floated
 * bare with no background treatment (Home tiles, Find My Car pin,
 * Onboarding steps, empty states).
 */
export default function IconBadge({ icon, tint = 'accentSoft', size = 56 }: IconBadgeProps) {
  const theme = useTheme();
  return (
    <View
      style={[
        styles.base,
        { backgroundColor: theme[tint], width: size, height: size, borderRadius: size },
      ]}
    >
      <Text style={{ fontSize: size * 0.5 }}>{icon}</Text>
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
