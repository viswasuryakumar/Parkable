import React from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import * as Location from 'expo-location';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ScanResult, VerdictResponse, checkParking, scanParking } from '../services/api';
import VerdictSummary from '../components/VerdictSummary';
import { useTheme, SPACING } from '../theme/colors';
import { addHistoryEntry } from '../utils/history';
import { startParkingSession } from '../utils/parkingSession';
import type { RootStackParamList } from '../navigation/types';
import IconBadge from '../components/IconBadge';
import AppButton from '../components/AppButton';

type FlowState =
  | { phase: 'preview' }
  | { phase: 'uploading' }
  | { phase: 'verdict'; verdict: VerdictResponse; lat: number; lng: number }
  | { phase: 'retake'; message: string }
  | { phase: 'error'; message: string };

export default function CameraScreen() {
  const theme = useTheme();
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const [state, setState] = React.useState<FlowState>({ phase: 'preview' });
  const [timerStarted, setTimerStarted] = React.useState(false);
  const [startingTimer, setStartingTimer] = React.useState(false);
  const [capturing, setCapturing] = React.useState(false);

  async function handleCapture() {
    setCapturing(true);
    try {
      const permission = await ImagePicker.requestCameraPermissionsAsync();
      if (!permission.granted) {
        setState({
          phase: 'error',
          message: permission.canAskAgain
            ? 'Camera access is required to scan a sign.'
            : 'Camera access is required. Enable it in your device settings.',
        });
        return;
      }

      // Delegates the actual capture to the phone's own camera app rather
      // than a custom in-app CameraX preview - found live that expo-camera's
      // CameraView.takePictureAsync failed unconditionally on a real device
      // ("Failed to capture image", CameraX's generic ImageCaptureException,
      // per expo-camera's own Android source) while the same device's stock
      // camera app worked fine. Delegating to that stock app sidesteps
      // whatever CameraX-specific issue this device (and possibly others)
      // has, at the cost of losing the custom in-app frame-guide overlay.
      const captured = await ImagePicker.launchCameraAsync({ quality: 1 });
      if (captured.canceled) {
        return;
      }
      const asset = captured.assets[0];

      setState({ phase: 'uploading' });

      // Resize + compress before upload, on both platforms identically (this
      // module has a real web implementation, unlike the old approach's
      // native/web split). A full-resolution phone photo is routinely
      // several MB - on a slow connection that either takes minutes or the
      // request just dies outright ("Failed to fetch", no HTTP response at
      // all, found live on a near-dead mobile signal). 1600px wide is still
      // plenty legible for reading sign text, and cuts the typical payload
      // by an order of magnitude regardless of network conditions.
      const manipulated = await ImageManipulator.manipulate(asset.uri).resize({ width: 1600 }).renderAsync();
      const saved = await manipulated.saveAsync({ base64: true, compress: 0.7, format: SaveFormat.JPEG });
      if (!saved.base64) {
        setState({ phase: 'error', message: 'Could not read the captured photo. Try again.' });
        return;
      }

      const locationPermission = await Location.requestForegroundPermissionsAsync();
      if (locationPermission.status !== 'granted') {
        setState({
          phase: 'error',
          message: 'Location permission is required so the sign is saved where it stands.',
        });
        return;
      }
      const position = await Location.getCurrentPositionAsync({
        accuracy: Location.Accuracy.Balanced,
      });
      const { latitude, longitude } = position.coords;
      const result: ScanResult = await scanParking({
        photo_base64: saved.base64,
        media_type: 'image/jpeg',
        lat: latitude,
        lng: longitude,
      });
      if (result.kind === 'needs_review') {
        setState({ phase: 'retake', message: result.message });
      } else if (result.kind === 'service_unavailable') {
        setState({ phase: 'error', message: result.message });
      } else {
        setTimerStarted(false);
        setState({ phase: 'verdict', verdict: result.verdict, lat: latitude, lng: longitude });
        // Best-effort: history is a nicety, never something that should
        // block or fail an otherwise-successful scan.
        addHistoryEntry({
          id: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
          scannedAt: new Date().toISOString(),
          lat: latitude,
          lng: longitude,
          verdict: result.verdict,
        }).catch(() => {});
      }
    } catch (error) {
      setState({
        phase: 'error',
        message: error instanceof Error ? error.message : 'Upload failed.',
      });
    } finally {
      setCapturing(false);
    }
  }

  async function startTimer() {
    if (state.phase !== 'verdict') {
      return;
    }
    setStartingTimer(true);
    try {
      // Re-check at THIS instant (when you actually park) rather than
      // trusting the scan-time verdict - walking back to the car takes time.
      const result = await checkParking(state.lat, state.lng);
      // /check can now answer with several distinct signs (see
      // CheckHandler.groupBySign) - this screen only ever shows ONE verdict
      // (the sign that was just scanned), so pick that same sign back out
      // by rule_id rather than silently doing nothing when the response
      // shape isn't the plain single-verdict one anymore. Falls back to the
      // closest sign if the rule_id can't be matched (e.g. it just expired).
      const verdict =
        result.kind === 'verdict'
          ? result.verdict
          : result.kind === 'multiple_signs'
            ? (result.signs.find((s) => s.rule_id === state.verdict.rule_id) ?? result.signs[0])
            : undefined;
      if (verdict) {
        setState({ phase: 'verdict', verdict, lat: state.lat, lng: state.lng });
        setTimerStarted(true);
        startParkingSession(state.lat, state.lng, verdict.valid_until ?? null).catch(() => {});
      }
    } catch {
      // Leave the prior verdict on screen; the button just stays available to retry.
    } finally {
      setStartingTimer(false);
    }
  }

  if (state.phase === 'uploading') {
    return (
      <View style={[styles.centered, { backgroundColor: theme.background }]}>
        <ActivityIndicator size="large" />
        <Text style={[styles.title, { color: theme.text }]}>Reading the sign…</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>Uploading your photo and extracting the rules.</Text>
      </View>
    );
  }

  if (state.phase === 'verdict') {
    return (
      <View style={[styles.centered, { backgroundColor: theme.background }]}>
        <VerdictSummary
          verdict={state.verdict}
          onStartTimer={startTimer}
          timerStarted={timerStarted}
          startingTimer={startingTimer}
          onReport={
            state.verdict.rule_id
              ? () => navigation.navigate('ReportSign', { ruleId: state.verdict.rule_id as string })
              : undefined
          }
        />
        <AppButton title="Scan another sign" onPress={() => setState({ phase: 'preview' })} />
      </View>
    );
  }

  if (state.phase === 'retake') {
    return (
      <View style={[styles.centered, { backgroundColor: theme.background }]}>
        <Text style={[styles.title, { color: theme.text }]}>Please retake the photo</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>{state.message}</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>
          Get closer, avoid glare, and fit the whole sign in the frame.
        </Text>
        <AppButton title="Retake" variant="primary" onPress={() => setState({ phase: 'preview' })} />
      </View>
    );
  }

  if (state.phase === 'error') {
    return (
      <View style={[styles.centered, { backgroundColor: theme.background }]}>
        <Text style={[styles.title, { color: theme.text }]}>Something went wrong</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>{state.message}</Text>
        <AppButton title="Try again" variant="primary" onPress={() => setState({ phase: 'preview' })} />
      </View>
    );
  }

  return (
    <View style={[styles.centered, { backgroundColor: theme.background }]}>
      <IconBadge icon="📷" tint="violetSoft" size={72} />
      <Text style={[styles.title, { color: theme.text }]}>Scan a parking sign</Text>
      <Text style={[styles.note, { color: theme.textMuted }]}>
        Fit the whole sign in the frame. Nothing is uploaded until after you take the photo.
      </Text>
      {capturing ? (
        <ActivityIndicator size="large" />
      ) : (
        <AppButton title="Open Camera" variant="primary" onPress={handleCapture} />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: SPACING.xl,
    gap: SPACING.md,
  },
  title: {
    fontSize: 24,
    fontWeight: '600',
    textAlign: 'center',
  },
  note: {
    textAlign: 'center',
  },
});
