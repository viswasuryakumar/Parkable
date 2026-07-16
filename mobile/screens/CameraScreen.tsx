import React from 'react';
import { Button, StyleSheet, Text, View } from 'react-native';
import { Camera } from 'expo-camera';
import * as Location from 'expo-location';

export default function CameraScreen() {
  const [permission, setPermission] = React.useState<'granted' | 'pending' | 'denied'>('pending');

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
      <Button title="Capture photo" onPress={() => {}} />
      <Text style={styles.note}>Low-confidence results will trigger a retake prompt.</Text>
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
