import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Icon, { IconName } from './Icon';
import { useTheme, SPACING, RADIUS, TYPE, Theme } from '../theme/colors';

type Props = {
  icon: IconName;
  label: string;
  sublabel?: string | null;
  /** Tint for the icon chip; defaults to a neutral recessed chip. */
  tone?: keyof Theme;
  onPress?: () => void;
  /** Last row in a group drops its divider. */
  last?: boolean;
};

/**
 * A tappable settings-style row: icon chip, label, chevron.
 *
 * Grouping these inside a single <Card> (rather than giving every action its
 * own floating card) is what stops a screen from reading as a pile of
 * unrelated boxes - related actions look related.
 */
export default function ListRow({ icon, label, sublabel, tone, onPress, last = false }: Props) {
  const theme = useTheme();

  const content = (
    <>
      <View style={[styles.chip, { backgroundColor: tone ? theme[tone] : theme.surfaceMuted }]}>
        <Icon name={icon} size={18} color="text" />
      </View>
      <View style={styles.labels}>
        <Text style={[styles.label, { color: theme.text }]}>{label}</Text>
        {sublabel ? (
          <Text style={[styles.sublabel, { color: theme.textMuted }]} numberOfLines={2}>
            {sublabel}
          </Text>
        ) : null}
      </View>
      {onPress ? <Icon name="chevronRight" size={18} color="textSubtle" /> : null}
    </>
  );

  const rowStyle = [
    styles.row,
    !last && { borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: theme.border },
  ];

  if (!onPress) {
    return <View style={rowStyle}>{content}</View>;
  }

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={sublabel ? `${label}. ${sublabel}` : label}
      onPress={onPress}
      style={({ pressed }) => [...rowStyle, pressed && { backgroundColor: theme.surfaceMuted }]}
    >
      {content}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.md,
    paddingVertical: SPACING.md,
    paddingHorizontal: SPACING.lg,
  },
  chip: {
    width: 34,
    height: 34,
    borderRadius: RADIUS.sm,
    alignItems: 'center',
    justifyContent: 'center',
  },
  labels: {
    flex: 1,
    gap: 1,
  },
  label: {
    ...TYPE.bodyStrong,
  },
  sublabel: {
    ...TYPE.caption,
  },
});
