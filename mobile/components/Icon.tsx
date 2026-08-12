import React from 'react';
import { Ionicons } from '@expo/vector-icons';
import { useTheme, Theme } from '../theme/colors';

/**
 * Every icon in the app, by meaning rather than by glyph.
 *
 * Two reasons this indirection earns its place. First, the app previously
 * drew icons as emoji (🏠 🅿️ 📷 ▲ 🚩), which render as a different picture on
 * every platform, ignore tint colour entirely, and never optically align with
 * the text beside them - the single loudest "unfinished prototype" signal in
 * the UI. Second, naming them semantically means swapping icon sets later is
 * this file, not a hunt through every screen.
 */
const ICONS = {
  // Navigation
  home: 'home',
  check: 'location',
  scan: 'camera',
  nearby: 'map',
  history: 'time',
  favorites: 'star',
  car: 'car-sport',
  admin: 'shield-checkmark',

  // Verdict states
  allowed: 'checkmark-circle',
  forbidden: 'close-circle',
  conditional: 'alert-circle',

  // Affordances
  chevronRight: 'chevron-forward',
  chevronDown: 'chevron-down',
  chevronUp: 'chevron-up',
  close: 'close',
  back: 'arrow-back',
  refresh: 'refresh',
  directions: 'navigate',
  flag: 'flag',
  info: 'information-circle',
  clock: 'time-outline',
  timer: 'stopwatch',
  bell: 'notifications',
  pin: 'location-sharp',
  source: 'document-text',
  camera: 'camera',
  photo: 'image',
  search: 'search',
  filter: 'options',
  warning: 'warning',
} as const;

export type IconName = keyof typeof ICONS;

type IconProps = {
  name: IconName;
  size?: number;
  /** A theme token, so icons can never drift off-palette. */
  color?: keyof Theme;
  /** Escape hatch for a colour already resolved from the theme. */
  tint?: string;
};

export default function Icon({ name, size = 20, color = 'text', tint }: IconProps) {
  const theme = useTheme();
  return <Ionicons name={ICONS[name]} size={size} color={tint ?? theme[color]} />;
}
