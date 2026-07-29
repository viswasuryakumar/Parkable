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
  // Pale tinted backgrounds for badges/chips/washes - carry the same
  // meaning as their solid counterpart without a full-saturation block
  // behind every icon and status label on screen.
  accentSoft: string;
  parkableSoft: string;
  notParkableSoft: string;
  dependsSoft: string;
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
  accentSoft: '#dbeafe',
  parkableSoft: '#dcfce7',
  notParkableSoft: '#fee2e2',
  dependsSoft: '#fef3c7',
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
  accentSoft: '#1e3a5f',
  parkableSoft: '#14532d',
  notParkableSoft: '#5f1d1d',
  dependsSoft: '#5c3d0a',
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

export const VERDICT_SOFT_COLOR_KEYS: Record<string, keyof Theme> = {
  PARKABLE: 'parkableSoft',
  NOT_PARKABLE: 'notParkableSoft',
  DEPENDS: 'dependsSoft',
};

// One shared scale instead of ad hoc literals (14 vs 16 vs 24 padding, etc.)
// scattered independently across every screen's StyleSheet.
export const SPACING = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 } as const;
export const RADIUS = { sm: 8, md: 12, lg: 16, xl: 20, pill: 999 } as const;
