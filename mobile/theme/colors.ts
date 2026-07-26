import { useColorScheme } from 'react-native';

/**
 * Single source of truth for colors used across the app. Previously every
 * screen repeated the same hex literals independently (#6b7280, #2563eb,
 * etc.) with no shared file - this consolidates them and adds dark-mode
 * variants driven by the system color scheme.
 */
export type Theme = {
  background: string;
  card: string;
  border: string;
  text: string;
  textMuted: string;
  accent: string;
  parkable: string;
  notParkable: string;
  depends: string;
};

const light: Theme = {
  background: '#ffffff',
  card: '#f3f4f6',
  border: '#e5e7eb',
  text: '#111827',
  textMuted: '#6b7280',
  accent: '#2563eb',
  parkable: '#16a34a',
  notParkable: '#dc2626',
  depends: '#d97706',
};

const dark: Theme = {
  background: '#0b0f14',
  card: '#1a2028',
  border: '#2a323d',
  text: '#f3f4f6',
  textMuted: '#9ca3af',
  accent: '#60a5fa',
  parkable: '#4ade80',
  notParkable: '#f87171',
  depends: '#fbbf24',
};

export function useTheme(): Theme {
  const scheme = useColorScheme();
  return scheme === 'dark' ? dark : light;
}

export const VERDICT_COLOR_KEYS: Record<string, keyof Theme> = {
  PARKABLE: 'parkable',
  NOT_PARKABLE: 'notParkable',
  DEPENDS: 'depends',
};
