import React from 'react';
import { ActivityIndicator, Button, StyleSheet, Text, View } from 'react-native';
import { Camera } from 'expo-camera';
import * as Location from 'expo-location';
import { scanParking } from '../services/api';

export default function CameraScreen() {
  const [permission, setPermission] = React.useState<'granted' | 'pending' | 'denied'>('pending');
  const [status, setStatus] = React.useState('Ready to inspect a sign.');
  const [uploading, setUploading] = React.useState(false);

  React.useEffect(() => {
    async function requestPermissions() {
      const cameraStatus = await Camera.requestCameraPermissionsAsync();
      const locationStatus = await Location.requestForegroundPermissionsAsync();
      if (cameraStatus.status === 'granted' && locationStatus.status === 'granted') {
        setPermission('granted');
      } else {
        setPermission('denied');
      }
    }

    requestPermissions();
  }, []);

  async function handleCapture() {
    setUploading(true);
    setStatus('Uploading sign photo…');

    try {
      const location = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Low });
      const payload = {
        photo_base64: 'placeholder-base64',
        media_type: 'image/jpeg',
        lat: location.coords.latitude,
        lng: location.coords.longitude,
      };
      const result = await scanParking(payload);
      setStatus(result.reason ?? result.verdict);
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Upload failed.');
    } finally {
      setUploading(false);
    }
  }

  if (permission !== 'granted') {
    return (
      <View style={styles.container}>
        <Text style={styles.title}>Camera and location access are required.</Text>
        <Text>We will ask for permission before uploading a photo.</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Camera flow</Text>
      <Text>Capture a sign photo and upload it for verification.</Text>
      {uploading ? <ActivityIndicator size="large" /> : null}
      <Button title="Capture photo" onPress={handleCapture} disabled={uploading} />
      <Text style={styles.note}>{status}</Text>
      <Text style={styles.note}>422 responses from the backend will prompt a retake flow.</Text>
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
  note: {
    color: '#6b7280',
    textAlign: 'center',
  },
});
