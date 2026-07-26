import AsyncStorage from '@react-native-async-storage/async-storage';
import { cancelNotification, scheduleNotification } from './notifications';

const STORAGE_KEY = 'parkable:parking_session';
const REMINDER_LEAD_MS = 10 * 60_000; // notify 10 minutes before the deadline

export type ParkingSession = {
  lat: number;
  lng: number;
  startedAt: string; // ISO instant
  validUntil: string | null; // ISO instant, or null for an untimed PARKABLE
  notificationId: string | null;
};

/**
 * "Where's my car" - saved the moment a parking timer actually starts (not
 * at scan/check time), since that's the instant that matters for both the
 * pin location and the reminder deadline. One session at a time: starting a
 * new timer replaces the old one and cancels its pending reminder, since
 * only one car can be parked "right now."
 */
export async function startParkingSession(lat: number, lng: number, validUntil: string | null): Promise<void> {
  await clearParkingSession();
  let notificationId: string | null = null;
  if (validUntil) {
    const fireAt = new Date(Date.parse(validUntil) - REMINDER_LEAD_MS);
    notificationId = await scheduleNotification(
      'Move your car soon',
      "Your parking time limit is almost up.",
      fireAt
    );
  }
  const session: ParkingSession = { lat, lng, startedAt: new Date().toISOString(), validUntil, notificationId };
  await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(session));
}

export async function getParkingSession(): Promise<ParkingSession | null> {
  const raw = await AsyncStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as ParkingSession;
  } catch {
    return null;
  }
}

export async function clearParkingSession(): Promise<void> {
  const existing = await getParkingSession();
  if (existing?.notificationId) {
    await cancelNotification(existing.notificationId);
  }
  await AsyncStorage.removeItem(STORAGE_KEY);
}
