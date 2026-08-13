import React from 'react';
import { ScrollView, StyleSheet, ViewStyle } from 'react-native';
import { useTheme, SPACING } from '../theme/colors';

type ScreenContainerProps = {
  children: React.ReactNode;
  /** Overrides for the content column - e.g. alignItems 'stretch' for forms. */
  contentStyle?: ViewStyle;
};

/**
 * A screen body that centres its content when it fits and scrolls when it
 * doesn't.
 *
 * Screens used to be a plain `flex: 1` View with justifyContent 'center',
 * which silently clips: a tall verdict (sign photo + timer card + trace +
 * buttons) overflowed both ends of the viewport at once, so the top of the
 * reason text AND the buttons at the bottom were simply unreachable, with no
 * scrollbar to hint that anything was missing. flexGrow on the content
 * container keeps the centring that short content wants without buying that.
 */
export default function ScreenContainer({ children, contentStyle }: ScreenContainerProps) {
  const theme = useTheme();
  return (
    <ScrollView
      style={[styles.scroll, { backgroundColor: theme.background }]}
      contentContainerStyle={[styles.content, contentStyle]}
      keyboardShouldPersistTaps="handled"
    >
      {children}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll: {
    flex: 1,
  },
  content: {
    flexGrow: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: SPACING.xl,
    gap: SPACING.md,
  },
});
