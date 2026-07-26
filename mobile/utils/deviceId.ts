import AsyncStorage from '@react-native-async-storage/async-storage';

const STORAGE_KEY = 'parkable:device_id';

/**
 * A random, anonymous, per-install identifier - not a user account. Used
 * only to attribute local actions (sign reports) without building any real
 * auth system. Generated once and persisted; a reinstall gets a new one,
 * which is fine since nothing server-side depends on continuity.
 */
export async function getDeviceId(): Promise<string> {
  const existing = await AsyncStorage.getItem(STORAGE_KEY);
  if (existing) {
    return existing;
  }
  const generated = generateUuid();
  await AsyncStorage.setItem(STORAGE_KEY, generated);
  return generated;
}

function generateUuid(): string {
  // crypto.randomUUID isn't available in the Hermes JS engine RN ships with,
  // so this is a plain Math.random-based v4-shaped id - fine for an
  // anonymous, non-cryptographic, purely-local identifier.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}
