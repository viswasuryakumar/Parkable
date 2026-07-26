import React from 'react';
import { ActivityIndicator, Button, StyleSheet, Text, TextInput, View } from 'react-native';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { reportRule } from '../services/api';
import { getDeviceId } from '../utils/deviceId';
import { useTheme } from '../theme/colors';
import type { RootStackParamList } from '../navigation/types';

const REASON_PRESETS = [
  'Sign is gone / removed',
  'Sign says something different',
  'Rule seems outdated',
];

type Phase = 'form' | 'sending' | 'sent' | 'error';

/**
 * Persists a report for later human review only - never automatically
 * changes or removes the rule (backend contract: ReportHandler/
 * RuleReportRepository never mutate the rules table).
 */
export default function ReportScreen() {
  const theme = useTheme();
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const route = useRoute();
  const { ruleId } = route.params as RootStackParamList['ReportSign'];
  const [reason, setReason] = React.useState('');
  const [phase, setPhase] = React.useState<Phase>('form');

  async function submit() {
    if (!reason.trim()) {
      return;
    }
    setPhase('sending');
    try {
      const deviceId = await getDeviceId();
      await reportRule(ruleId, reason.trim(), deviceId);
      setPhase('sent');
    } catch {
      setPhase('error');
    }
  }

  if (phase === 'sent') {
    return (
      <View style={[styles.container, { backgroundColor: theme.background }]}>
        <Text style={[styles.title, { color: theme.text }]}>Thanks for the report</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>
          We'll take a look. This doesn't change what other drivers see right away.
        </Text>
        <Button title="Done" onPress={() => navigation.goBack()} />
      </View>
    );
  }

  return (
    <View style={[styles.container, { backgroundColor: theme.background }]}>
      <Text style={[styles.title, { color: theme.text }]}>Report an issue</Text>
      <Text style={[styles.note, { color: theme.textMuted }]}>
        What's wrong with this sign's data?
      </Text>
      <View style={styles.presets}>
        {REASON_PRESETS.map((preset) => (
          <Button key={preset} title={preset} onPress={() => setReason(preset)} />
        ))}
      </View>
      <TextInput
        value={reason}
        onChangeText={setReason}
        placeholder="Describe the issue"
        placeholderTextColor={theme.textMuted}
        style={[styles.input, { borderColor: theme.border, color: theme.text }]}
        multiline
      />
      {phase === 'error' ? (
        <Text style={[styles.note, { color: theme.notParkable }]}>
          Couldn't send the report. Check your connection and try again.
        </Text>
      ) : null}
      {phase === 'sending' ? (
        <ActivityIndicator />
      ) : (
        <Button title="Submit report" onPress={submit} disabled={!reason.trim()} />
      )}
      <Button title="Cancel" onPress={() => navigation.goBack()} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 24,
    gap: 12,
    justifyContent: 'center',
  },
  title: {
    fontSize: 22,
    fontWeight: '700',
    textAlign: 'center',
  },
  note: {
    textAlign: 'center',
    fontSize: 14,
  },
  presets: {
    gap: 6,
  },
  input: {
    borderWidth: 1,
    borderRadius: 10,
    padding: 12,
    minHeight: 80,
    textAlignVertical: 'top',
  },
});
