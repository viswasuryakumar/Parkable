import React from 'react';
import { Button, SafeAreaView, StyleSheet, View } from 'react-native';
import VerdictScreen from './screens/VerdictScreen';
import CameraScreen from './screens/CameraScreen';

type Screen = 'verdict' | 'camera';

export default function App() {
  const [screen, setScreen] = React.useState<Screen>('verdict');

  return (
    <SafeAreaView style={styles.root}>
      {screen === 'verdict' ? (
        <VerdictScreen onScanRequested={() => setScreen('camera')} />
      ) : (
        <CameraScreen />
      )}
      <View style={styles.switcher}>
        <Button
          title={screen === 'verdict' ? 'Scan a sign' : 'Back to verdict'}
          onPress={() => setScreen(screen === 'verdict' ? 'camera' : 'verdict')}
        />
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  switcher: {
    padding: 16,
  },
});
