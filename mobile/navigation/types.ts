/** Bottom tab routes - the always-visible main screens. */
export type TabParamList = {
  Check: undefined;
  Scan: undefined;
  Nearby: undefined;
  History: undefined;
  Favorites: undefined;
};

/**
 * Root stack: hosts the tab navigator plus anything that should stack ON
 * TOP of it (modals, detail screens) rather than replace it - the hand-
 * rolled tab switcher this replaces couldn't express that at all.
 */
export type RootStackParamList = {
  Tabs: undefined;
  ReportSign: { ruleId: string };
};
