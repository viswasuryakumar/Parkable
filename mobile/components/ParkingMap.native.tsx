import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import MapView, { Callout, Marker, Region } from 'react-native-maps';
import { useTheme } from '../theme/colors';
import { ParkingMapProps, focusDeltas, resolveFocus } from './ParkingMap.types';

/**
 * Native only - react-native-maps has no web renderer at all. Metro resolves
 * this file on iOS/Android via the .native.tsx suffix and ParkingMap.web.tsx
 * (Leaflet + OpenStreetMap) for web builds, so react-native-maps is never
 * even imported into the web bundle. Both take the same props.
 */
export default function ParkingMap(props: ParkingMapProps) {
  const theme = useTheme();
  const mapRef = React.useRef<MapView>(null);
  const focus = resolveFocus(props);
  const { latDelta, lngDelta } = focusDeltas(focus);
  const region: Region = {
    latitude: focus.center.lat,
    longitude: focus.center.lng,
    latitudeDelta: latDelta,
    longitudeDelta: lngDelta,
  };

  // initialRegion only applies at mount, but the focus routinely settles a
  // moment later (Find My Car has no GPS fix for the first second, so it
  // starts framed on the car alone). Animating on change rather than passing
  // a controlled `region` means the map still belongs to the user's fingers
  // in between - a controlled region snaps back on every pan.
  React.useEffect(() => {
    mapRef.current?.animateToRegion(region, 400);
    // Only a real viewport change should move the camera.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [region.latitude, region.longitude, region.latitudeDelta]);

  return (
    <MapView
      ref={mapRef}
      style={styles.map}
      initialRegion={region}
      showsUserLocation
      showsMyLocationButton={false}
    >
      {props.markers.map((marker) => (
        <Marker
          key={marker.key}
          coordinate={{ latitude: marker.lat, longitude: marker.lng }}
          pinColor={marker.variant === 'car' ? theme.parkable : undefined}
        >
          <Callout>
            <View style={styles.callout}>
              <Text style={[styles.calloutText, { color: theme.text }]} numberOfLines={3}>
                {marker.description}
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
