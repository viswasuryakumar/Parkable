import AsyncStorage from '@react-native-async-storage/async-storage';
import { VerdictResponse } from '../services/api';

const STORAGE_KEY = 'parkable:scan_history';
const MAX_ENTRIES = 100;

export type HistoryEntry = {
  id: string;
  scannedAt: string; // ISO instant
  lat: number;
  lng: number;
  verdict: VerdictResponse;
};

/**
 * Purely local (device-only) history of signs YOU scanned - not /check
 * results (those are "what's here now," not "something I read"), matching
 * the same scan-time scoping as photo_url/confidence. No backend sync; a
 * reinstall loses it, which is an accepted tradeoff for not building real
 * accounts just for this.
 */
export async function addHistoryEntry(entry: HistoryEntry): Promise<void> {
  const existing = await getHistory();
  const updated = [entry, ...existing].slice(0, MAX_ENTRIES);
  await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(updated));
}

export async function getHistory(): Promise<HistoryEntry[]> {
  const raw = await AsyncStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return [];
  }
  try {
    return JSON.parse(raw) as HistoryEntry[];
  } catch {
    return [];
  }
}

export async function clearHistory(): Promise<void> {
  await AsyncStorage.removeItem(STORAGE_KEY);
}
