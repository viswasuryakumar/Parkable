import { Platform, TextStyle, useColorScheme } from 'react-native';

/**
 * Single source of truth for colors, type and elevation.
 *
 * Layering note: the page is a soft grey and cards are white (inverted in
 * dark mode). The previous palette did the opposite - white page, grey cards
 * - which gave every surface the same visual weight, so nothing on screen
 * read as raised or more important than anything else.
 */
export type Theme = {
  /** Page background - intentionally not pure white, so cards can sit above it. */
  background: string;
  /** Raised surface: cards, sheets, rows. */
  card: string;
  /** Recessed surface: chips, inputs, inactive segments. */
  surfaceMuted: string;
  border: string;
  /** For dividers that need to carry structure rather than just whisper. */
  borderStrong: string;
  text: string;
  textMuted: string;
  /** Lowest-emphasis text: timestamps, disclaimers, metadata. */
  textSubtle: string;
  accent: string;
  onAccent: string;
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
  // Purely decorative accents - no verdict meaning attached (unlike
  // parkable/notParkable/depends, which must stay reserved for actual
  // verdicts so a color never silently implies "this is safe/unsafe to
  // park"). Used to give Home's feature tiles visual variety instead of
  // every tile reading as the same flat blue.
  violet: string;
  violetSoft: string;
  teal: string;
  tealSoft: string;
  rose: string;
  roseSoft: string;
};

const light: Theme = {
  background: '#f4f5f7',
  card: '#ffffff',
  surfaceMuted: '#eef0f3',
  border: '#e4e7eb',
  borderStrong: '#d0d5db',
  text: '#0f172a',
  textMuted: '#5b6472',
  textSubtle: '#8b95a3',
  accent: '#2563eb',
  onAccent: '#ffffff',
  parkable: '#15803d',
  notParkable: '#dc2626',
  depends: '#b45309',
  accentSoft: '#dbeafe',
  parkableSoft: '#dcfce7',
  notParkableSoft: '#fee2e2',
  dependsSoft: '#fef3c7',
  violet: '#7c3aed',
  violetSoft: '#ede9fe',
  teal: '#0d9488',
  tealSoft: '#ccfbf1',
  rose: '#e11d48',
  roseSoft: '#ffe4e6',
};

const dark: Theme = {
  background: '#0b0f14',
  card: '#151b23',
  surfaceMuted: '#1e2632',
  border: '#252d38',
  borderStrong: '#374151',
  text: '#f1f5f9',
  textMuted: '#98a2b3',
  textSubtle: '#6b7684',
  accent: '#60a5fa',
  onAccent: '#0b0f14',
  parkable: '#4ade80',
  notParkable: '#f87171',
  depends: '#fbbf24',
  accentSoft: '#17304f',
  parkableSoft: '#13361f',
  notParkableSoft: '#41191c',
  dependsSoft: '#40300d',
  violet: '#a78bfa',
  violetSoft: '#2e2450',
  teal: '#2dd4bf',
  tealSoft: '#0f3d3a',
  rose: '#fb7185',
  roseSoft: '#45161f',
};

export function useTheme(): Theme {
  const scheme = useColorScheme();
  return scheme === 'dark' ? dark : light;
}

export function useIsDark(): boolean {
  return useColorScheme() === 'dark';
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

/**
 * Type scale. Previously every screen picked its own fontSize/fontWeight, so
 * "a heading" was 32 in one place, 26 in another and 22 in a third, with no
 * line heights at all - which is what made body copy run together.
 */
export const TYPE = {
  display: { fontSize: 30, fontWeight: '800', lineHeight: 36 },
  title: { fontSize: 22, fontWeight: '700', lineHeight: 28 },
  heading: { fontSize: 17, fontWeight: '700', lineHeight: 23 },
  body: { fontSize: 15, fontWeight: '400', lineHeight: 22 },
  bodyStrong: { fontSize: 15, fontWeight: '600', lineHeight: 22 },
  label: { fontSize: 13, fontWeight: '600', lineHeight: 18 },
  caption: { fontSize: 12, fontWeight: '400', lineHeight: 17 },
  /** Uppercase section eyebrow. */
  overline: { fontSize: 11, fontWeight: '700', lineHeight: 14, letterSpacing: 0.6 },
} as const satisfies Record<string, TextStyle>;

/**
 * Shadows are three different APIs for one effect - web wants boxShadow,
 * Android wants elevation, iOS wants the shadow* quartet - so each level is
 * defined once here rather than re-Platform.select'ed at every call site.
 */
export const ELEVATION = {
  card: Platform.select({
    web: { boxShadow: '0 1px 2px rgba(15,23,42,0.06), 0 4px 12px rgba(15,23,42,0.05)' },
    android: { elevation: 2 },
    default: {
      shadowColor: '#0f172a',
      shadowOffset: { width: 0, height: 2 },
      shadowOpacity: 0.08,
      shadowRadius: 8,
    },
  }),
  raised: Platform.select({
    web: { boxShadow: '0 2px 4px rgba(15,23,42,0.08), 0 12px 28px rgba(15,23,42,0.10)' },
    android: { elevation: 6 },
    default: {
      shadowColor: '#0f172a',
      shadowOffset: { width: 0, height: 6 },
      shadowOpacity: 0.14,
      shadowRadius: 16,
    },
  }),
} as const;
