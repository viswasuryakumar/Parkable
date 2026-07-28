import React from 'react';
import { Pressable, StyleSheet, Text, ViewStyle } from 'react-native';
import { useTheme } from '../theme/colors';

type AppButtonProps = {
  title: string;
  onPress: () => void;
  variant?: 'primary' | 'secondary';
  disabled?: boolean;
  style?: ViewStyle;
};

/**
 * Themed replacement for React Native's plain <Button> - that renders as an
 * unstyled platform-default control (a flat blue Android/iOS button, or a
 * bare grey box on web) with no way to touch its look. Every screen used it
 * directly, which is why the Check tab read as "just some blue buttons."
 */
export default function AppButton({ title, onPress, variant = 'secondary', disabled = false, style }: AppButtonProps) {
  const theme = useTheme();
  const isPrimary = variant === 'primary';

  return (
    <Pressable
      style={({ pressed }) => [
        styles.base,
        isPrimary
          ? { backgroundColor: theme.accent }
          : { backgroundColor: 'transparent', borderWidth: 1, borderColor: theme.border },
        (pressed || disabled) && { opacity: disabled ? 0.5 : 0.85 },
        style,
      ]}
      onPress={onPress}
      disabled={disabled}
    >
      <Text style={[styles.text, { color: isPrimary ? '#ffffff' : theme.text }]}>{title}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    borderRadius: 10,
    paddingVertical: 13,
    paddingHorizontal: 24,
    alignItems: 'center',
    minWidth: 160,
  },
  text: {
    fontSize: 15,
    fontWeight: '700',
  },
});
