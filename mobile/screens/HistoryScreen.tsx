import React from 'react';
import { Button, FlatList, Image, StyleSheet, Text, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { HistoryEntry, clearHistory, getHistory } from '../utils/history';
import { useTheme } from '../theme/colors';

const VERDICT_LABELS: Record<string, string> = {
  PARKABLE: 'Parkable',
  NOT_PARKABLE: 'Not parkable',
  DEPENDS: 'Depends',
};

function formatWhen(iso: string): string {
  const date = new Date(iso);
  const diffMs = Date.now() - date.getTime();
  const diffHours = Math.floor(diffMs / 3_600_000);
  if (diffHours < 1) {
    return 'Just now';
  }
  if (diffHours < 24) {
    return `${diffHours}h ago`;
  }
  const diffDays = Math.floor(diffHours / 24);
  return `${diffDays}d ago`;
}

export default function HistoryScreen() {
  const theme = useTheme();
  const [entries, setEntries] = React.useState<HistoryEntry[] | null>(null);

  const load = React.useCallback(() => {
    getHistory().then(setEntries);
  }, []);

  // Refetch every time this screen becomes focused, not just on first mount -
  // a scan taken since the last visit must show up without a manual refresh.
  useFocusEffect(load);

  async function handleClear() {
    await clearHistory();
    setEntries([]);
  }

  if (entries === null) {
    return <View style={[styles.centered, { backgroundColor: theme.background }]} />;
  }

  if (entries.length === 0) {
    return (
      <View style={[styles.centered, { backgroundColor: theme.background }]}>
        <Text style={[styles.title, { color: theme.text }]}>No scans yet</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>
          Signs you scan will show up here.
        </Text>
      </View>
    );
  }

  return (
    <View style={[styles.container, { backgroundColor: theme.background }]}>
      <FlatList
        data={entries}
        keyExtractor={(entry) => entry.id}
        contentContainerStyle={styles.listContent}
        renderItem={({ item }) => {
          const colorKey = item.verdict.verdict === 'PARKABLE' ? 'parkable'
            : item.verdict.verdict === 'NOT_PARKABLE' ? 'notParkable' : 'depends';
          return (
            <View style={[styles.card, { backgroundColor: theme.card }]}>
              {item.verdict.photo_url ? (
                <Image source={{ uri: item.verdict.photo_url }} style={styles.thumb} />
              ) : (
                <View style={[styles.thumb, styles.thumbPlaceholder, { backgroundColor: theme.border }]} />
              )}
              <View style={styles.cardBody}>
                <Text style={[styles.verdictLabel, { color: theme[colorKey] }]}>
                  {VERDICT_LABELS[item.verdict.verdict] ?? item.verdict.verdict}
                </Text>
                {item.verdict.reason ? (
                  <Text style={[styles.reason, { color: theme.textMuted }]} numberOfLines={2}>
                    {item.verdict.reason}
                  </Text>
                ) : null}
                <Text style={[styles.when, { color: theme.textMuted }]}>{formatWhen(item.scannedAt)}</Text>
              </View>
            </View>
          );
        }}
      />
      <Button title="Clear history" onPress={handleClear} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 16,
    gap: 12,
  },
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
    gap: 8,
  },
  title: {
    fontSize: 20,
    fontWeight: '600',
  },
  note: {
    textAlign: 'center',
  },
  listContent: {
    gap: 8,
  },
  card: {
    flexDirection: 'row',
    borderRadius: 12,
    overflow: 'hidden',
    gap: 10,
    padding: 10,
  },
  thumb: {
    width: 64,
    height: 64,
    borderRadius: 8,
  },
  thumbPlaceholder: {},
  cardBody: {
    flex: 1,
    justifyContent: 'center',
    gap: 2,
  },
  verdictLabel: {
    fontSize: 15,
    fontWeight: '700',
  },
  reason: {
    fontSize: 13,
  },
  when: {
    fontSize: 12,
    marginTop: 2,
  },
});
