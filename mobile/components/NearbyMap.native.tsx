import React from 'react';
import { StyleSheet } from 'react-native';
import MapView, { Marker, Callout } from 'react-native-maps';
import { Text, View } from 'react-native';
import { useTheme } from '../theme/colors';

export type MapSignGroup = {
  key: string;
  lat: number;
  lng: number;
  description: string;
};

type Props = {
  userLat: number;
  userLng: number;
  groups: MapSignGroup[];
};

/**
 * Native only - react-native-maps has no web renderer. NearbyScreen picks
 * between this and the existing list view; Metro resolves this file
 * automatically on iOS/Android via the .native.tsx suffix, and picks
 * NearbyMap.web.tsx for web builds, so react-native-maps is never even
 * imported into the web bundle.
 */
export default function NearbyMap({ userLat, userLng, groups }: Props) {
  const theme = useTheme();
  return (
    <MapView
      style={styles.map}
      initialRegion={{
        latitude: userLat,
        longitude: userLng,
        latitudeDelta: 0.01,
        longitudeDelta: 0.01,
      }}
      showsUserLocation
    >
      {groups.map((group) => (
        <Marker key={group.key} coordinate={{ latitude: group.lat, longitude: group.lng }}>
          <Callout>
            <View style={styles.callout}>
              <Text style={[styles.calloutText, { color: theme.text }]} numberOfLines={3}>
                {group.description}
              </Text>
            </View>
          </Callout>
        </Marker>
      ))}
    </MapView>
  );
}

const styles = StyleSheet.create({
  map: {
    flex: 1,
  },
  callout: {
    maxWidth: 220,
    padding: 4,
  },
  calloutText: {
    fontSize: 13,
  },
});
