import { Platform } from 'react-native';
import * as Notifications from 'expo-notifications';

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
  }),
});

/**
 * Local notifications need a dev-client/standalone build on Expo SDK 54 -
 * they silently no-op in plain Expo Go, so a failure/no-permission here must
 * degrade honestly (return false), never throw and break the calling flow
 * (starting a parking timer, saving a favorite reminder, etc.).
 */
export async function ensureNotificationPermission(): Promise<boolean> {
  if (Platform.OS === 'web') {
    return false;
  }
  const existing = await Notifications.getPermissionsAsync();
  if (existing.granted) {
    return true;
  }
  const requested = await Notifications.requestPermissionsAsync();
  return requested.granted;
}

/** Returns the scheduled notification id (for later cancellation), or null if it couldn't be scheduled. */
export async function scheduleNotification(
  title: string,
  body: string,
  fireAt: Date
): Promise<string | null> {
  if (fireAt.getTime() <= Date.now()) {
    return null;
  }
  const hasPermission = await ensureNotificationPermission();
  if (!hasPermission) {
    return null;
  }
  try {
    return await Notifications.scheduleNotificationAsync({
      content: { title, body },
      trigger: { type: Notifications.SchedulableTriggerInputTypes.DATE, date: fireAt },
    });
  } catch {
    return null;
  }
}

export async function cancelNotification(id: string): Promise<void> {
  try {
    await Notifications.cancelScheduledNotificationAsync(id);
  } catch {
    // Already fired/cancelled - nothing to do.
  }
}
