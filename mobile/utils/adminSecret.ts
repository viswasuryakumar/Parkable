import AsyncStorage from '@react-native-async-storage/async-storage';

const STORAGE_KEY = 'parkable:admin_secret';

/**
 * Gates the Admin screen. Not a real login - there's no user-account system
 * anywhere in this app - just the same shared secret the backend checks
 * against PARKABLE_ADMIN_SECRET (see ReportsHandler), typed in once by the
 * owner and remembered locally.
 */
export async function getAdminSecret(): Promise<string | null> {
  return AsyncStorage.getItem(STORAGE_KEY);
}

export async function setAdminSecret(secret: string): Promise<void> {
  await AsyncStorage.setItem(STORAGE_KEY, secret);
}

export async function clearAdminSecret(): Promise<void> {
  await AsyncStorage.removeItem(STORAGE_KEY);
}
