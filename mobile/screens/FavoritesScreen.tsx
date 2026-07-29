import React from 'react';
import { ActivityIndicator, FlatList, StyleSheet, Text, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { Favorite, getFavorites, removeFavorite } from '../utils/favorites';
import { CheckResult, checkParking } from '../services/api';
import VerdictSummary from '../components/VerdictSummary';
import { useTheme, SPACING } from '../theme/colors';
import Card from '../components/Card';
import IconBadge from '../components/IconBadge';
import AppButton from '../components/AppButton';

export default function FavoritesScreen() {
  const theme = useTheme();
  const [favorites, setFavorites] = React.useState<Favorite[] | null>(null);
  const [checking, setChecking] = React.useState<string | null>(null);
  const [results, setResults] = React.useState<Record<string, CheckResult>>({});

  const load = React.useCallback(() => {
    getFavorites().then(setFavorites);
  }, []);

  useFocusEffect(load);

  async function handleCheck(favorite: Favorite) {
    setChecking(favorite.id);
    try {
      const result = await checkParking(favorite.lat, favorite.lng);
      setResults((prev) => ({ ...prev, [favorite.id]: result }));
    } finally {
      setChecking(null);
    }
  }

  async function handleRemove(id: string) {
    await removeFavorite(id);
    setFavorites((prev) => (prev ?? []).filter((f) => f.id !== id));
    setResults((prev) => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
  }

  if (favorites === null) {
    return <View style={[styles.centered, { backgroundColor: theme.background }]} />;
  }

  if (favorites.length === 0) {
    return (
      <View style={[styles.centered, { backgroundColor: theme.background }]}>
        <IconBadge icon="⭐" />
        <Text style={[styles.title, { color: theme.text }]}>No favorites yet</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>
          Save a spot from the Check tab (e.g. home or work) to quickly re-check it later.
        </Text>
      </View>
    );
  }

  return (
    <View style={[styles.container, { backgroundColor: theme.background }]}>
      <FlatList
        data={favorites}
        keyExtractor={(item) => item.id}
        contentContainerStyle={styles.listContent}
        renderItem={({ item }) => {
          const result = results[item.id];
          return (
            <Card style={styles.card}>
              <View style={styles.cardHeader}>
                <Text style={[styles.label, { color: theme.text }]}>{item.label}</Text>
                <AppButton title="Remove" onPress={() => handleRemove(item.id)} />
              </View>
              {checking === item.id ? (
                <ActivityIndicator />
              ) : result?.kind === 'verdict' ? (
                <VerdictSummary verdict={result.verdict} />
              ) : result?.kind === 'no_data' ? (
                <Text style={[styles.note, { color: theme.textMuted }]}>{result.message}</Text>
              ) : (
                <AppButton title="Check now" variant="primary" onPress={() => handleCheck(item)} />
              )}
            </Card>
          );
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: SPACING.lg,
  },
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: SPACING.xl,
    gap: SPACING.sm,
  },
  title: {
    fontSize: 20,
    fontWeight: '600',
  },
  note: {
    textAlign: 'center',
  },
  listContent: {
    gap: SPACING.md,
  },
  card: {
    padding: SPACING.md + 2,
    gap: SPACING.md,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  label: {
    fontSize: 16,
    fontWeight: '700',
  },
});
