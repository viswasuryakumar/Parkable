import React from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  RefreshControl,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useTheme } from '../theme/colors';
import { clearAdminSecret, getAdminSecret, setAdminSecret } from '../utils/adminSecret';
import { fetchReports, SignReport } from '../services/api';

function formatReportedAt(iso: string): string {
  return new Date(iso).toLocaleString();
}

/**
 * Owner-only screen listing "report an issue" submissions. Gated by a
 * shared secret (not a real login - see utils/adminSecret) that the backend
 * independently checks on every request, so a stale/guessed local value
 * still can't read anything.
 */
export default function AdminScreen() {
  const theme = useTheme();
  const [secret, setSecret] = React.useState<string | null | undefined>(undefined);
  const [secretInput, setSecretInput] = React.useState('');
  const [reports, setReports] = React.useState<SignReport[] | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  React.useEffect(() => {
    getAdminSecret().then(setSecret);
  }, []);

  const load = React.useCallback(async (withSecret: string) => {
    setLoading(true);
    setError(null);
    try {
      const result = await fetchReports(withSecret);
      if (result.kind === 'unauthorized') {
        setError('That secret was rejected. Clear it and try again.');
        setReports(null);
      } else {
        setReports(result.reports);
      }
    } catch {
      setError('Could not reach the server. Check your connection and try again.');
    } finally {
      setLoading(false);
    }
  }, []);

  React.useEffect(() => {
    if (secret) {
      load(secret);
    }
  }, [secret, load]);

  const submitSecret = async () => {
    const trimmed = secretInput.trim();
    if (!trimmed) return;
    await setAdminSecret(trimmed);
    setSecret(trimmed);
  };

  const forgetSecret = async () => {
    await clearAdminSecret();
    setSecret(null);
    setReports(null);
    setSecretInput('');
  };

  if (secret === undefined) {
    return <View style={[styles.container, { backgroundColor: theme.background }]} />;
  }

  if (!secret) {
    return (
      <View style={[styles.container, { backgroundColor: theme.background }]}>
        <Text style={[styles.title, { color: theme.text }]}>Admin access</Text>
        <Text style={[styles.subtitle, { color: theme.textMuted }]}>
          Enter the admin secret to review sign reports.
        </Text>
        <TextInput
          style={[styles.input, { borderColor: theme.border, color: theme.text }]}
          value={secretInput}
          onChangeText={setSecretInput}
          placeholder="Admin secret"
          placeholderTextColor={theme.textMuted}
          secureTextEntry
          autoCapitalize="none"
          autoCorrect={false}
        />
        <Pressable
          style={[styles.primaryButton, { backgroundColor: theme.accent, opacity: secretInput.trim() ? 1 : 0.5 }]}
          onPress={submitSecret}
          disabled={!secretInput.trim()}
        >
          <Text style={styles.primaryButtonText}>Continue</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <View style={[styles.container, { backgroundColor: theme.background }]}>
      <View style={styles.header}>
        <Text style={[styles.title, { color: theme.text }]}>Reported signs</Text>
        <Pressable onPress={forgetSecret} hitSlop={8}>
          <Text style={[styles.forgetLink, { color: theme.textMuted }]}>Log out</Text>
        </Pressable>
      </View>

      {error ? <Text style={[styles.error, { color: theme.notParkable }]}>{error}</Text> : null}

      {loading && reports === null ? (
        <ActivityIndicator style={styles.loading} />
      ) : (
        <FlatList
          data={reports ?? []}
          keyExtractor={(item) => item.id}
          refreshControl={
            <RefreshControl refreshing={loading} onRefresh={() => load(secret)} tintColor={theme.accent} />
          }
          contentContainerStyle={reports && reports.length === 0 ? styles.emptyContainer : styles.list}
          ListEmptyComponent={
            <Text style={[styles.emptyText, { color: theme.textMuted }]}>No reports yet.</Text>
          }
          renderItem={({ item }) => (
            <View style={[styles.card, { backgroundColor: theme.card, borderColor: theme.border }]}>
              <Text style={[styles.ruleId, { color: theme.text }]}>{item.rule_id}</Text>
              <Text style={[styles.reason, { color: theme.text }]}>{item.reason}</Text>
              <Text style={[styles.meta, { color: theme.textMuted }]}>
                {formatReportedAt(item.reported_at)} · device {item.device_id.slice(0, 8)}
              </Text>
            </View>
          )}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 20,
    gap: 16,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  title: {
    fontSize: 22,
    fontWeight: '700',
  },
  subtitle: {
    fontSize: 14,
    lineHeight: 20,
  },
  input: {
    borderWidth: 1,
    borderRadius: 10,
    padding: 12,
    fontSize: 16,
  },
  primaryButton: {
    borderRadius: 10,
    paddingVertical: 14,
    alignItems: 'center',
  },
  primaryButtonText: {
    color: '#ffffff',
    fontSize: 16,
    fontWeight: '700',
  },
  forgetLink: {
    fontSize: 13,
    textDecorationLine: 'underline',
  },
  error: {
    fontSize: 14,
  },
  loading: {
    marginTop: 24,
  },
  list: {
    gap: 10,
  },
  emptyContainer: {
    flexGrow: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  emptyText: {
    fontSize: 14,
  },
  card: {
    borderRadius: 12,
    borderWidth: 1,
    padding: 14,
    gap: 6,
  },
  ruleId: {
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 0.3,
  },
  reason: {
    fontSize: 15,
    lineHeight: 20,
  },
  meta: {
    fontSize: 12,
  },
});
