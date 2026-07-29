import React from 'react';
import { ActivityIndicator, Platform, StyleSheet, Text, View } from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import * as FileSystem from 'expo-file-system/legacy';
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

/**
 * Native returns bare base64 (read separately via expo-file-system - see
 * handleCapture); the web build returns a full data URL
 * (data:image/png;base64,...). The API contract wants bare base64 plus an
 * explicit media_type, so normalize here and derive the real type from the
 * prefix when present (web captures PNG, not JPEG).
 */
function normalizePhoto(raw: string): { base64: string; mediaType: string } {
  const match = raw.match(/^data:(image\/[a-z+.-]+);base64,(.*)$/s);
  if (match) {
    return { mediaType: match[1], base64: match[2] };
  }
  return { mediaType: 'image/jpeg', base64: raw };
}

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
      const captured = await ImagePicker.launchCameraAsync({ quality: 0.85 });
      if (captured.canceled) {
        return;
      }
      const asset = captured.assets[0];

      setState({ phase: 'uploading' });

      // Read the file back separately rather than requesting base64 from
      // the picker directly - same reasoning as the capture path itself:
      // don't trust an in-process native encode step more than necessary.
      // Web already returns a data: URL directly from the picker.
      let rawBase64: string | undefined;
      if (Platform.OS === 'web') {
        rawBase64 = asset.uri;
      } else {
        rawBase64 = await FileSystem.readAsStringAsync(asset.uri, { encoding: 'base64' });
      }
      if (!rawBase64) {
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
      const { base64, mediaType } = normalizePhoto(rawBase64);
      const { latitude, longitude } = position.coords;
      const result: ScanResult = await scanParking({
        photo_base64: base64,
        media_type: mediaType,
        lat: latitude,
        lng: longitude,
      });
      if (result.kind === 'needs_review') {
        setState({ phase: 'retake', message: result.message });
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
      if (result.kind === 'verdict') {
        setState({ phase: 'verdict', verdict: result.verdict, lat: state.lat, lng: state.lng });
        setTimerStarted(true);
        startParkingSession(state.lat, state.lng, result.verdict.valid_until ?? null).catch(() => {});
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
      <IconBadge icon="📷" size={72} />
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
