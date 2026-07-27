import React from 'react';
import { LogBox, SafeAreaView, StyleSheet, Text, useColorScheme, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { StatusBar } from 'expo-status-bar';
import RootNavigator from './navigation/RootNavigator';
import { useTheme } from './theme/colors';

// Expo Go (SDK 53+) dropped remote push support and logs this as a
// console.error, which React Native's LogBox escalates to a blocking
// full-screen overlay on every launch. We only ever use LOCAL notifications
// (scheduleNotificationAsync for Find My Car / reminders) - never request a
// push token - so this specific warning is expected noise, not a real
// error. A real dev-client/standalone build won't hit this at all.
LogBox.ignoreLogs(['expo-notifications: Android Push notifications']);

export default function App() {
  const theme = useTheme();
  const scheme = useColorScheme();
  return (
    <SafeAreaProvider>
      <StatusBar style={scheme === 'dark' ? 'light' : 'dark'} />
      <SafeAreaView style={[styles.root, { backgroundColor: theme.background }]}>
        <View style={[styles.header, { borderBottomColor: theme.border }]}>
          <Text style={[styles.headerTitle, { color: theme.text }]}>Parkable</Text>
          <Text style={[styles.headerSubtitle, { color: theme.textMuted }]}>
            Can I park here right now?
          </Text>
        </View>
        <View style={styles.navigatorFill}>
          <RootNavigator />
        </View>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  header: {
    paddingHorizontal: 20,
    paddingTop: 12,
    paddingBottom: 8,
    borderBottomWidth: 1,
  },
  headerTitle: {
    fontSize: 22,
    fontWeight: '700',
  },
  headerSubtitle: {
    fontSize: 13,
  },
  navigatorFill: {
    flex: 1,
  },
});
