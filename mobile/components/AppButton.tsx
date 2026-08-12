import React from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View, ViewStyle } from 'react-native';
import Icon, { IconName } from './Icon';
import { useTheme, RADIUS, SPACING, TYPE } from '../theme/colors';

type AppButtonProps = {
  title: string;
  onPress: () => void;
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  size?: 'md' | 'lg';
  icon?: IconName;
  disabled?: boolean;
  loading?: boolean;
  /** Primary calls to action should span the column they sit in. */
  fullWidth?: boolean;
  style?: ViewStyle;
};

/**
 * Themed replacement for React Native's plain <Button> - that renders as an
 * unstyled platform-default control (a flat blue Android/iOS button, or a
 * bare grey box on web) with no way to touch its look. Every screen used it
 * directly, which is why the Check tab read as "just some blue buttons."
 */
export default function AppButton({
  title,
  onPress,
  variant = 'secondary',
  size = 'md',
  icon,
  disabled = false,
  loading = false,
  fullWidth = false,
  style,
}: AppButtonProps) {
  const theme = useTheme();

  const fills: Record<string, ViewStyle> = {
    primary: { backgroundColor: theme.accent },
    danger: { backgroundColor: theme.notParkable },
    secondary: {
      backgroundColor: theme.card,
      borderWidth: StyleSheet.hairlineWidth,
      borderColor: theme.borderStrong,
    },
    ghost: { backgroundColor: 'transparent' },
  };
  const labelColor =
    variant === 'primary' || variant === 'danger' ? theme.onAccent : theme.text;

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: disabled || loading, busy: loading }}
      style={({ pressed }) => [
        styles.base,
        size === 'lg' ? styles.lg : styles.md,
        fills[variant],
        fullWidth && styles.fullWidth,
        // Disabled reads as flat and faded; pressed only dips slightly, so a
        // real tap still feels distinct from an unavailable control.
        disabled && styles.disabled,
        pressed && !disabled && styles.pressed,
        style,
      ]}
      onPress={onPress}
      disabled={disabled || loading}
    >
      {loading ? (
        <ActivityIndicator color={labelColor} />
      ) : (
        <View style={styles.content}>
          {icon ? <Icon name={icon} size={18} tint={labelColor} /> : null}
          <Text style={[styles.text, { color: labelColor }]} numberOfLines={1}>
            {title}
          </Text>
        </View>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    borderRadius: RADIUS.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  md: {
    paddingVertical: 13,
    paddingHorizontal: SPACING.xl,
    minWidth: 150,
  },
  lg: {
    paddingVertical: 16,
    paddingHorizontal: SPACING.xl,
    minWidth: 180,
  },
  fullWidth: {
    alignSelf: 'stretch',
    width: '100%',
  },
  content: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.sm,
  },
  text: {
    ...TYPE.bodyStrong,
    fontWeight: '700',
  },
  pressed: {
    opacity: 0.88,
    transform: [{ scale: 0.985 }],
  },
  disabled: {
    opacity: 0.45,
  },
});
