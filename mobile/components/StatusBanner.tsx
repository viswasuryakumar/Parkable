import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import Icon, { IconName } from './Icon';
import { useTheme, SPACING, RADIUS, TYPE, Theme } from '../theme/colors';

export type StatusTone = 'allowed' | 'forbidden' | 'conditional' | 'neutral';

type Props = {
  tone: StatusTone;
  /** The answer itself, in as few words as possible. */
  title: string;
  /** The qualifier under the rule: "Not allowed until 6:00 pm". */
  detail?: string | null;
  children?: React.ReactNode;
};

const TONE: Record<StatusTone, { fill: keyof Theme; line: keyof Theme; icon: IconName }> = {
  allowed: { fill: 'parkableSoft', line: 'parkable', icon: 'allowed' },
  forbidden: { fill: 'notParkableSoft', line: 'notParkable', icon: 'forbidden' },
  conditional: { fill: 'dependsSoft', line: 'depends', icon: 'conditional' },
  neutral: { fill: 'surfaceMuted', line: 'textMuted', icon: 'info' },
};

/**
 * The verdict, as one unmistakable block.
 *
 * The old layout was a centred stack of loose text - a coloured headline, a
 * grey reason, a countdown - with nothing binding it together, so the single
 * most important answer on the screen carried no more visual weight than the
 * paragraph under it. Here the colour, the border and the icon all say the
 * same thing at once, and the divider separates the verdict from its
 * qualifier so "No parking" and "until 6:00 pm" are never misread as one
 * run-on sentence.
 *
 * Colour is never the only channel: the icon differs per tone too, so the
 * verdict survives colour-blindness and a greyscale screenshot.
 */
export default function StatusBanner({ tone, title, detail, children }: Props) {
  const theme = useTheme();
  const palette = TONE[tone];
  const lineColor = theme[palette.line];

  return (
    <View style={[styles.banner, { backgroundColor: theme[palette.fill], borderColor: lineColor }]}>
      <View style={styles.titleRow}>
        <Icon name={palette.icon} size={26} tint={lineColor} />
        <Text style={[styles.title, { color: lineColor }]}>{title}</Text>
      </View>

      {detail ? (
        <>
          <View style={[styles.divider, { backgroundColor: lineColor }]} />
          <Text style={[styles.detail, { color: theme.text }]}>{detail}</Text>
        </>
      ) : null}

      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  banner: {
    borderWidth: 1.5,
    borderRadius: RADIUS.lg,
    padding: SPACING.lg,
    gap: SPACING.md,
  },
  titleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.sm,
  },
  title: {
    ...TYPE.title,
    flex: 1,
  },
  divider: {
    height: StyleSheet.hairlineWidth,
    // The rule reads as part of the banner rather than a separate element.
    opacity: 0.45,
  },
  detail: {
    ...TYPE.body,
  },
});
