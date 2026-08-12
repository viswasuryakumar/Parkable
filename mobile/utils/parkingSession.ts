import AsyncStorage from '@react-native-async-storage/async-storage';
import { Platform } from 'react-native';
import { cancelNotification, scheduleNotification } from './notifications';
import { syncWebPushReminders } from './webPush';

const STORAGE_KEY = 'parkable:parking_session';
const REMINDER_LEAD_MS = 10 * 60_000; // notify 10 minutes before the deadline

export type ParkingSession = {
  lat: number;
  lng: number;
  startedAt: string; // ISO instant
  validUntil: string | null; // ISO instant, or null for an untimed PARKABLE
  notificationIds: string[];
};

/** Shape written before the deadline reminder was added; still on disk for anyone mid-session. */
type LegacyParkingSession = Omit<ParkingSession, 'notificationIds'> & { notificationId: string | null };

/**
 * "Where's my car" - saved the moment a parking timer actually starts (not
 * at scan/check time), since that's the instant that matters for both the
 * pin location and the reminder deadline. One session at a time: starting a
 * new timer replaces the old one and cancels its pending reminder, since
 * only one car can be parked "right now."
 */
export async function startParkingSession(lat: number, lng: number, validUntil: string | null): Promise<void> {
  await clearParkingSession();
  const notificationIds: string[] = [];

  // Two different mechanisms for the same promise. Native schedules locally
  // with the OS; the browser has no equivalent API, so the deadline is
  // registered with the server and pushed back at the right moment. Failures
  // on either path must not stop the session itself from being saved - the
  // countdown on screen is still useful without a reminder.
  if (Platform.OS === 'web') {
    if (validUntil) {
      await syncWebPushReminders(validUntil).catch(() => false);
    }
    const session: ParkingSession = {
      lat, lng, startedAt: new Date().toISOString(), validUntil, notificationIds,
    };
    await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    return;
  }

  if (validUntil) {
    const deadline = Date.parse(validUntil);
    // Two reminders, not one. The 10-minute warning is the actionable one -
    // it's the only one that leaves time to actually walk back and move the
    // car - but on its own it's silently useless for any limit shorter than
    // 10 minutes, where scheduleNotification() drops an already-past fire
    // time. The deadline notification is the backstop that always fires.
    const scheduled = await Promise.all([
      scheduleNotification(
        'Move your car soon',
        'Your parking time limit is almost up.',
        new Date(deadline - REMINDER_LEAD_MS)
      ),
      scheduleNotification(
        'Your parking time is up',
        'The time limit where you parked has expired. Move your car to avoid a ticket.',
        new Date(deadline)
      ),
    ]);
    notificationIds.push(...scheduled.filter((id): id is string => id !== null));
  }
  const session: ParkingSession = { lat, lng, startedAt: new Date().toISOString(), validUntil, notificationIds };
  await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(session));
}

export async function getParkingSession(): Promise<ParkingSession | null> {
  const raw = await AsyncStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as ParkingSession | LegacyParkingSession;
    if ('notificationIds' in parsed) {
      return parsed;
    }
    // Migrate a session stored by the single-reminder version in place, so a
    // car parked across the upgrade still cancels its pending reminder.
    const { notificationId, ...rest } = parsed;
    return { ...rest, notificationIds: notificationId ? [notificationId] : [] };
  } catch {
    return null;
  }
}

export async function clearParkingSession(): Promise<void> {
  const existing = await getParkingSession();
  if (existing) {
    await Promise.all(existing.notificationIds.map(cancelNotification));
  }
  if (Platform.OS === 'web' && existing?.validUntil) {
    // Cancel server-side too, or the push still arrives after the car has
    // moved - the one failure mode that would teach people to ignore it.
    await syncWebPushReminders(null).catch(() => false);
  }
  await AsyncStorage.removeItem(STORAGE_KEY);
}
