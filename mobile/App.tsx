import React from 'react';
import { Pressable, SafeAreaView, StyleSheet, Text, View } from 'react-native';
import VerdictScreen from './screens/VerdictScreen';
import CameraScreen from './screens/CameraScreen';
import NearbyScreen from './screens/NearbyScreen';

type Tab = 'check' | 'scan' | 'nearby';

const TABS: { key: Tab; label: string; icon: string }[] = [
  { key: 'check', label: 'Check', icon: '🅿️' },
  { key: 'scan', label: 'Scan', icon: '📷' },
  { key: 'nearby', label: 'Nearby', icon: '📍' },
];

export default function App() {
  const [tab, setTab] = React.useState<Tab>('check');

  return (
    <SafeAreaView style={styles.root}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>Parkable</Text>
        <Text style={styles.headerSubtitle}>Can I park here right now?</Text>
      </View>
      <View style={styles.content}>
        {tab === 'check' ? <VerdictScreen onScanRequested={() => setTab('scan')} /> : null}
        {tab === 'scan' ? <CameraScreen /> : null}
        {tab === 'nearby' ? <NearbyScreen /> : null}
      </View>
      <View style={styles.tabBar}>
        {TABS.map((item) => {
          const active = item.key === tab;
          return (
            <Pressable
              key={item.key}
              style={[styles.tabButton, active && styles.tabButtonActive]}
              onPress={() => setTab(item.key)}
            >
              <Text style={styles.tabIcon}>{item.icon}</Text>
              <Text style={[styles.tabLabel, active && styles.tabLabelActive]}>{item.label}</Text>
            </Pressable>
          );
        })}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#ffffff',
  },
  header: {
    paddingHorizontal: 20,
    paddingTop: 12,
    paddingBottom: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#e5e7eb',
  },
  headerTitle: {
    fontSize: 22,
    fontWeight: '700',
    color: '#111827',
  },
  headerSubtitle: {
    fontSize: 13,
    color: '#6b7280',
  },
  content: {
    flex: 1,
  },
  tabBar: {
    flexDirection: 'row',
    borderTopWidth: 1,
    borderTopColor: '#e5e7eb',
    backgroundColor: '#ffffff',
  },
  tabButton: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: 10,
    gap: 2,
  },
  tabButtonActive: {
    backgroundColor: '#eff6ff',
  },
  tabIcon: {
    fontSize: 20,
  },
  tabLabel: {
    fontSize: 12,
    color: '#6b7280',
  },
  tabLabelActive: {
    color: '#2563eb',
    fontWeight: '600',
  },
});
