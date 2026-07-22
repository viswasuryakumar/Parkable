import React from 'react';
import { ActivityIndicator, Button, StyleSheet, Text, View } from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';
import * as Location from 'expo-location';
import { ScanResult, VerdictResponse, scanParking } from '../services/api';

type FlowState =
  | { phase: 'preview' }
  | { phase: 'uploading' }
  | { phase: 'verdict'; verdict: VerdictResponse }
  | { phase: 'retake'; message: string }
  | { phase: 'error'; message: string };

const VERDICT_COLORS: Record<string, string> = {
  PARKABLE: '#16a34a',
  NOT_PARKABLE: '#dc2626',
  DEPENDS: '#d97706',
};

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

export default function CameraScreen() {
  const [cameraPermission, requestCameraPermission] = useCameraPermissions();
  const [state, setState] = React.useState<FlowState>({ phase: 'preview' });
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
      // quality 0.5 keeps the base64 payload well under API Gateway's 10MB
      // limit while staying readable for extraction (plan D3: base64 v1).
      const photo = await camera.takePictureAsync({ base64: true, quality: 0.5 });
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
      const result: ScanResult = await scanParking({
        photo_base64: base64,
        media_type: mediaType,
        lat: position.coords.latitude,
        lng: position.coords.longitude,
      });
      if (result.kind === 'needs_review') {
        setState({ phase: 'retake', message: result.message });
      } else {
        setState({ phase: 'verdict', verdict: result.verdict });
      }
    } catch (error) {
      setState({
        phase: 'error',
        message: error instanceof Error ? error.message : 'Upload failed.',
      });
    }
  }

  if (!cameraPermission?.granted) {
    return (
      <View style={styles.centered}>
        <Text style={styles.title}>Camera access is required</Text>
        <Text style={styles.note}>
          Parkable reads the parking sign from your photo. Nothing is uploaded until you tap
          capture.
        </Text>
        {cameraPermission?.canAskAgain === false ? (
          <Text style={styles.note}>Enable camera access in your device settings.</Text>
        ) : (
          <Button title="Grant camera access" onPress={requestCameraPermission} />
        )}
      </View>
    );
  }

  if (state.phase === 'uploading') {
    return (
      <View style={styles.centered}>
        <ActivityIndicator size="large" />
        <Text style={styles.title}>Reading the sign…</Text>
        <Text style={styles.note}>Uploading your photo and extracting the rules.</Text>
      </View>
    );
  }

  if (state.phase === 'verdict') {
    const color = VERDICT_COLORS[state.verdict.verdict] ?? '#111827';
    return (
      <View style={styles.centered}>
        <Text style={[styles.verdict, { color }]}>{state.verdict.verdict.replace('_', ' ')}</Text>
        {state.verdict.reason ? <Text style={styles.note}>{state.verdict.reason}</Text> : null}
        <Button title="Scan another sign" onPress={() => setState({ phase: 'preview' })} />
      </View>
    );
  }

  if (state.phase === 'retake') {
    return (
      <View style={styles.centered}>
        <Text style={styles.title}>Please retake the photo</Text>
        <Text style={styles.note}>{state.message}</Text>
        <Text style={styles.note}>
          Get closer, avoid glare, and fit the whole sign in the frame.
        </Text>
        <Button title="Retake" onPress={() => setState({ phase: 'preview' })} />
      </View>
    );
  }

  if (state.phase === 'error') {
    return (
      <View style={styles.centered}>
        <Text style={styles.title}>Something went wrong</Text>
        <Text style={styles.note}>{state.message}</Text>
        <Button title="Try again" onPress={() => setState({ phase: 'preview' })} />
      </View>
    );
  }

  return (
    <View style={styles.cameraContainer}>
      <CameraView ref={cameraRef} style={styles.camera} facing="back" />
      <View style={styles.controls}>
        <Text style={styles.note}>Fit the whole parking sign in the frame.</Text>
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
  camera: {
    flex: 1,
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
  verdict: {
    fontSize: 32,
    fontWeight: '700',
  },
  note: {
    color: '#6b7280',
    textAlign: 'center',
  },
});
