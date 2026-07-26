import React from 'react';
import { ActivityIndicator, Button, Platform, StyleSheet, Text, View } from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';
import * as Location from 'expo-location';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ScanResult, VerdictResponse, checkParking, scanParking } from '../services/api';
import VerdictSummary from '../components/VerdictSummary';
import { useTheme } from '../theme/colors';
import { addHistoryEntry } from '../utils/history';
import { startParkingSession } from '../utils/parkingSession';
import type { RootStackParamList } from '../navigation/types';

type FlowState =
  | { phase: 'preview' }
  | { phase: 'uploading' }
  | { phase: 'verdict'; verdict: VerdictResponse; lat: number; lng: number }
  | { phase: 'retake'; message: string }
  | { phase: 'error'; message: string };

/**
 * Native takePictureAsync returns bare base64; the web build returns a full
 * data URL (data:image/png;base64,...). The API contract wants bare base64
 * plus an explicit media_type, so normalize here and derive the real type
 * from the prefix when present (web captures PNG, not JPEG).
 */
function normalizePhoto(raw: string): { base64: string; mediaType: string } {
  const match = raw.match(/^data:(image\/[a-z+.-]+);base64,(.*)$/s);
  if (match) {
    return { mediaType: match[1], base64: match[2] };
  }
  return { mediaType: 'image/jpeg', base64: raw };
}

/**
 * expo-camera's web preview is CSS-mirrored ONLY for a front-facing camera
 * (verified in its source: ExpoCamera.web.js applies scaleX(-1) exactly when
 * native.type === 'front'). A laptop has no true back camera, so requesting
 * facing="back" silently falls back to the only camera it has — the
 * front-facing webcam — which IS mirrored, and needs a counter-flip so
 * framing matches what's actually uploaded. A phone's browser has a real
 * back/environment camera, which is NOT mirrored to begin with — applying
 * the same counter-flip there un-does nothing and just mirrors a correct
 * image (a real bug this project hit). Distinguishing the two needs a
 * platform check finer than "is this web", since RN Web reports web for
 * both: sniff the user agent for a mobile OS.
 */
function isMobileWebBrowser(): boolean {
  if (Platform.OS !== 'web' || typeof navigator === 'undefined') {
    return false;
  }
  return /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
}

export default function CameraScreen() {
  const theme = useTheme();
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const [cameraPermission, requestCameraPermission] = useCameraPermissions();
  const [state, setState] = React.useState<FlowState>({ phase: 'preview' });
  const [timerStarted, setTimerStarted] = React.useState(false);
  const [startingTimer, setStartingTimer] = React.useState(false);
  const cameraRef = React.useRef<CameraView>(null);

  React.useEffect(() => {
    if (cameraPermission && !cameraPermission.granted && cameraPermission.canAskAgain) {
      requestCameraPermission();
    }
  }, [cameraPermission, requestCameraPermission]);

  async function handleCapture() {
    const camera = cameraRef.current;
    if (!camera) {
      return;
    }
    setState({ phase: 'uploading' });
    try {
      // 0.5 (the original setting) over-compressed real signs into unreadable
      // mush - a user's photo came out blurrier than their phone's own camera
      // app, and re-scans kept needing retries. 0.85 is still nowhere near
      // API Gateway's 10MB body limit (a phone/webcam photo at this quality
      // is typically well under 1MB), so there's no real tradeoff here -
      // 0.5 was just too aggressive for what it was trying to protect against.
      const photo = await camera.takePictureAsync({ base64: true, quality: 0.85 });
      if (!photo?.base64) {
        setState({ phase: 'error', message: 'Could not capture a photo. Try again.' });
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
      const { base64, mediaType } = normalizePhoto(photo.base64);
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

  if (!cameraPermission?.granted) {
    return (
      <View style={[styles.centered, { backgroundColor: theme.background }]}>
        <Text style={[styles.title, { color: theme.text }]}>Camera access is required</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>
          Parkable reads the parking sign from your photo. Nothing is uploaded until you tap
          capture.
        </Text>
        {cameraPermission?.canAskAgain === false ? (
          <Text style={[styles.note, { color: theme.textMuted }]}>Enable camera access in your device settings.</Text>
        ) : (
          <Button title="Grant camera access" onPress={requestCameraPermission} />
        )}
      </View>
    );
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
        <Button title="Scan another sign" onPress={() => setState({ phase: 'preview' })} />
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
        <Button title="Retake" onPress={() => setState({ phase: 'preview' })} />
      </View>
    );
  }

  if (state.phase === 'error') {
    return (
      <View style={[styles.centered, { backgroundColor: theme.background }]}>
        <Text style={[styles.title, { color: theme.text }]}>Something went wrong</Text>
        <Text style={[styles.note, { color: theme.textMuted }]}>{state.message}</Text>
        <Button title="Try again" onPress={() => setState({ phase: 'preview' })} />
      </View>
    );
  }

  const needsUnmirror = Platform.OS === 'web' && !isMobileWebBrowser();

  return (
    <View style={styles.cameraContainer}>
      {/* Counter-flip only a desktop browser's forced-front webcam preview
          (see isMobileWebBrowser doc above) — a phone browser's real back
          camera must render untouched. Either way the captured PHOTO itself
          was never mirrored (expo-camera's takePicture never sets
          isImageMirror); this transform only affects what's on screen while
          framing the shot. */}
      <View style={needsUnmirror ? styles.unmirror : styles.cameraFill}>
        <CameraView ref={cameraRef} style={styles.cameraFill} facing="back" />
      </View>
      <View style={styles.controls}>
        <Text style={[styles.note, { color: theme.textMuted }]}>Fit the whole parking sign in the frame.</Text>
        <Button title="Capture" onPress={handleCapture} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
    gap: 12,
  },
  cameraContainer: {
    flex: 1,
  },
  cameraFill: {
    flex: 1,
  },
  unmirror: {
    flex: 1,
    transform: [{ scaleX: -1 }],
  },
  controls: {
    padding: 16,
    gap: 8,
    alignItems: 'center',
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
