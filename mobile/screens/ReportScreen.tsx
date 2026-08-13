import React from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { reportRule } from '../services/api';
import { getDeviceId } from '../utils/deviceId';
import { useTheme, SPACING, RADIUS } from '../theme/colors';
import type { RootStackParamList } from '../navigation/types';
import AppButton from '../components/AppButton';
import ScreenContainer from '../components/ScreenContainer';

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
      <ScreenContainer>
        <Text style={[styles.title, { color: theme.text }]}>Thanks for the report</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>
          We'll take a look. This doesn't change what other drivers see right away.
        </Text>
        <AppButton title="Done" variant="primary" onPress={() => navigation.goBack()} />
      </ScreenContainer>
    );
  }

  return (
    <ScreenContainer contentStyle={styles.form}>
      <Text style={[styles.title, { color: theme.text }]}>Report an issue</Text>
      <Text style={[styles.note, { color: theme.textMuted }]}>
        What's wrong with this sign's data?
      </Text>
      <View style={styles.chipRow}>
        {REASON_PRESETS.map((preset) => {
          const selected = reason === preset;
          return (
            <Pressable
              key={preset}
              onPress={() => setReason(preset)}
              style={[
                styles.chip,
                {
                  backgroundColor: selected ? theme.accentSoft : theme.card,
                  borderColor: selected ? theme.accent : theme.border,
                },
              ]}
            >
              <Text style={[styles.chipText, { color: selected ? theme.accent : theme.text }]}>
                {preset}
              </Text>
            </Pressable>
          );
        })}
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
        <AppButton title="Submit report" variant="primary" onPress={submit} disabled={!reason.trim()} />
      )}
      <AppButton title="Cancel" onPress={() => navigation.goBack()} />
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  // The text input and buttons want the full column width, not the centred
  // shrink-wrap the default screen body gives short content.
  form: {
    alignItems: 'stretch',
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
  chipRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: SPACING.sm,
    justifyContent: 'center',
  },
  chip: {
    borderWidth: 1,
    borderRadius: RADIUS.pill,
    paddingVertical: SPACING.sm,
    paddingHorizontal: SPACING.md,
  },
  chipText: {
    fontSize: 13,
    fontWeight: '600',
  },
  input: {
    borderWidth: 1,
    borderRadius: RADIUS.md,
    padding: SPACING.md,
    minHeight: 80,
    textAlignVertical: 'top',
  },
});
