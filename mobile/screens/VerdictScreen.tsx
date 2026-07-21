import React from 'react';
import { ActivityIndicator, Button, StyleSheet, Text, View } from 'react-native';
import { checkParking } from '../services/api';

type VerdictScreenProps = {
  lat?: number;
  lng?: number;
};

export default function VerdictScreen({ lat = 37.7749, lng = -122.4194 }: VerdictScreenProps) {
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);
  const [verdict, setVerdict] = React.useState<string>('PENDING');
  const [reason, setReason] = React.useState('Waiting for a fresh backend response.');

  React.useEffect(() => {
    let active = true;

    async function loadVerdict() {
      try {
        const result = await checkParking(lat, lng);
        if (!active) {
          return;
        }
        setVerdict(result.verdict);
        setReason(result.reason ?? 'No specific reason was returned.');
      } catch (e) {
        if (!active) {
          return;
        }
        setError(e instanceof Error ? e.message : 'Unknown error');
      } finally {
        if (active) {
          setLoading(false);
        }
      }
    }

    loadVerdict();
    return () => {
      active = false;
    };
  }, [lat, lng]);

  if (loading) {
    return (
      <View style={styles.container}>
        <ActivityIndicator size="large" />
        <Text style={styles.title}>Checking your spot…</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Parking verdict</Text>
      <Text style={styles.verdict}>{verdict}</Text>
      <Text style={styles.reason}>{reason}</Text>
      {error ? <Text style={styles.error}>Error: {error}</Text> : null}
      <Button title="Retake photo" onPress={() => {}} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
    gap: 12,
  },
  title: {
    fontSize: 24,
    fontWeight: '600',
  },
  verdict: {
    fontSize: 20,
    fontWeight: '700',
  },
  reason: {
    textAlign: 'center',
    color: '#4b5563',
  },
  error: {
    color: '#dc2626',
  },
});
