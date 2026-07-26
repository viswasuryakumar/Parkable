import AsyncStorage from '@react-native-async-storage/async-storage';

const STORAGE_KEY = 'parkable:onboarding_seen';

export async function hasSeenOnboarding(): Promise<boolean> {
  return (await AsyncStorage.getItem(STORAGE_KEY)) === 'true';
}

export async function markOnboardingSeen(): Promise<void> {
  await AsyncStorage.setItem(STORAGE_KEY, 'true');
}
