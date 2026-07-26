import React from 'react';
import { Button, StyleSheet, Text, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTheme } from '../theme/colors';
import { markOnboardingSeen } from '../utils/onboarding';
import type { RootStackParamList } from '../navigation/types';

const STEPS = [
  {
    icon: '🅿️',
    title: 'Can I park here right now?',
    body: "Parkable answers with a rule, not a guess - every verdict traces back to an actual sign or government record, never an AI's opinion.",
  },
  {
    icon: '📷',
    title: 'Scan a sign, get a verdict',
    body: "Point your camera at a parking sign. We read it, and a plain rules engine - not the AI - decides the verdict.",
  },
  {
    icon: '🤔',
    title: 'Honest uncertainty',
    body: "If a sign is too blurry to read confidently, we'll say so and ask for a retake - never a confident wrong answer.",
  },
];

export default function OnboardingScreen() {
  const theme = useTheme();
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const [step, setStep] = React.useState(0);
  const isLast = step === STEPS.length - 1;
  const current = STEPS[step];

  async function finish() {
    await markOnboardingSeen();
    navigation.replace('Tabs');
  }

  return (
    <View style={[styles.container, { backgroundColor: theme.background }]}>
      <View style={styles.content}>
        <Text style={styles.icon}>{current.icon}</Text>
        <Text style={[styles.title, { color: theme.text }]}>{current.title}</Text>
        <Text style={[styles.body, { color: theme.textMuted }]}>{current.body}</Text>
      </View>
      <View style={styles.dots}>
        {STEPS.map((_, index) => (
          <View
            key={index}
            style={[
              styles.dot,
              { backgroundColor: index === step ? theme.accent : theme.border },
            ]}
          />
        ))}
      </View>
      <View style={styles.actions}>
        {isLast ? (
          <Button title="Get started" onPress={finish} />
        ) : (
          <>
            <Button title="Skip" onPress={finish} />
            <Button title="Next" onPress={() => setStep((s) => s + 1)} />
          </>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 24,
    justifyContent: 'space-between',
  },
  content: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    gap: 14,
  },
  icon: {
    fontSize: 56,
  },
  title: {
    fontSize: 24,
    fontWeight: '700',
    textAlign: 'center',
  },
  body: {
    fontSize: 15,
    lineHeight: 22,
    textAlign: 'center',
  },
  dots: {
    flexDirection: 'row',
    justifyContent: 'center',
    gap: 8,
    marginBottom: 16,
  },
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  actions: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
});
