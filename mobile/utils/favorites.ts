import AsyncStorage from '@react-native-async-storage/async-storage';

const STORAGE_KEY = 'parkable:favorites';

export type Favorite = {
  id: string;
  label: string;
  lat: number;
  lng: number;
  savedAt: string; // ISO instant
};

/** Local-only saved spots (e.g. "Home", "Work") for a quick re-check later. */
export async function getFavorites(): Promise<Favorite[]> {
  const raw = await AsyncStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return [];
  }
  try {
    return JSON.parse(raw) as Favorite[];
  } catch {
    return [];
  }
}

export async function addFavorite(favorite: Favorite): Promise<void> {
  const existing = await getFavorites();
  await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify([favorite, ...existing]));
}

export async function removeFavorite(id: string): Promise<void> {
  const existing = await getFavorites();
  await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(existing.filter((f) => f.id !== id)));
}
