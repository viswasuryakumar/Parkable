/** Bottom tab routes - kept small (4) so it stays scannable; everything
 * else lives one tap away via Home's quick actions or the root stack. */
export type TabParamList = {
  Home: undefined;
  Check: undefined;
  Scan: undefined;
  Nearby: undefined;
};

/**
 * Root stack: hosts the tab navigator plus anything that should stack ON
 * TOP of it (modals, detail screens) rather than replace it - the hand-
 * rolled tab switcher this replaces couldn't express that at all.
 */
export type RootStackParamList = {
  Onboarding: undefined;
  Tabs: undefined;
  ReportSign: { ruleId: string };
  History: undefined;
  Favorites: undefined;
  FindMyCar: undefined;
};
