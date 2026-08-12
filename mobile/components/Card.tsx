import React from 'react';
import { Pressable, StyleSheet, View, ViewProps, ViewStyle } from 'react-native';
import { useTheme, RADIUS, ELEVATION } from '../theme/colors';

type CardProps = ViewProps & {
  /** When provided, the card renders as a Pressable instead of a plain View. */
  onPress?: () => void;
  /** Drops the internal padding for rows/lists that manage their own. */
  flush?: boolean;
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
export default function Card({ style, children, onPress, flush = false, ...rest }: CardProps) {
  const theme = useTheme();
  // A hairline border as well as a shadow: shadows are nearly invisible
  // against a dark background, so without it dark mode loses every card edge.
  const baseStyle = [
    styles.base,
    { backgroundColor: theme.card, borderColor: theme.border },
    ELEVATION.card as ViewStyle,
    flush && styles.flush,
  ];

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
    opacity: 0.9,
    transform: [{ scale: 0.99 }],
  },
  base: {
    borderRadius: RADIUS.lg,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
  },
  flush: {
    padding: 0,
  },
});
