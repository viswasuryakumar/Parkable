import React from 'react';
import { Platform, Pressable, StyleSheet, View, ViewProps, ViewStyle } from 'react-native';
import { useTheme, RADIUS } from '../theme/colors';

type CardProps = ViewProps & {
  /** When provided, the card renders as a Pressable instead of a plain View. */
  onPress?: () => void;
};

/**
 * Shared card surface - every screen used to hand-roll its own
 * {borderRadius, padding, backgroundColor} block with no shadow, so cards
 * read as flat colored rectangles rather than tappable/liftable surfaces.
 * One definition here instead of N slightly-different copies. Doubles as a
 * pressable surface (Home's tiles, Favorites' rows) via the optional onPress
 * rather than forcing every caller to duplicate the same visual styling on
 * a separately-wrapped Pressable.
 */
export default function Card({ style, children, onPress, ...rest }: CardProps) {
  const theme = useTheme();
  const baseStyle = [styles.base, { backgroundColor: theme.card, shadowColor: theme.text }];

  if (onPress) {
    return (
      <Pressable
        style={({ pressed }) => [...baseStyle, pressed && styles.pressed, style as ViewStyle]}
        onPress={onPress}
      >
        {children}
      </Pressable>
    );
  }

  return (
    <View style={[...baseStyle, style]} {...rest}>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  pressed: {
    opacity: 0.85,
  },
  base: {
    borderRadius: RADIUS.lg,
    // Web ignores shadowColor/Offset/Opacity/Radius in favor of boxShadow,
    // and ignores `elevation` entirely - three code paths for one visual
    // effect, all plain StyleSheet properties, no new dependency.
    ...Platform.select({
      web: { boxShadow: '0 1px 3px rgba(0,0,0,0.08), 0 1px 2px rgba(0,0,0,0.06)' },
      android: { elevation: 2 },
      default: {
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.08,
        shadowRadius: 3,
      },
    }),
  },
});
