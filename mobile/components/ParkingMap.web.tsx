import React from 'react';
import 'leaflet/dist/leaflet.css';
import L from 'leaflet';
import { CircleMarker, MapContainer, Marker, Popup, TileLayer, useMap } from 'react-leaflet';
import { ParkingMapProps, focusDeltas, resolveFocus } from './ParkingMap.types';

// Leaflet's default marker icon resolves image paths relative to the JS
// bundle location, which breaks under Metro's web bundler (same well-known
// issue under Webpack/Vite) - point at the package's own hosted images
// instead of fighting local asset resolution for a one-time icon.
delete (L.Icon.Default.prototype as unknown as { _getIconUrl?: unknown })._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png',
  iconUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png',
  shadowUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png',
});

// Drawn rather than fetched, so "where's my car" never depends on a CDN image
// loading - and so it can't be mistaken for one of the blue sign pins.
const CAR_ICON = L.divIcon({
  className: '',
  html: '<div style="font-size:26px;line-height:32px;text-align:center;filter:drop-shadow(0 1px 2px rgba(15,23,42,0.45))">🚗</div>',
  iconSize: [32, 32],
  iconAnchor: [16, 16],
  popupAnchor: [0, -16],
});

function boundsFor(props: ParkingMapProps): L.LatLngBoundsLiteral {
  const focus = resolveFocus(props);
  const { latDelta, lngDelta } = focusDeltas(focus);
  return [
    [focus.center.lat - latDelta / 2, focus.center.lng - lngDelta / 2],
    [focus.center.lat + latDelta / 2, focus.center.lng + lngDelta / 2],
  ];
}

/**
 * MapContainer's `bounds` prop is initial-state only, so this keeps the
 * viewport following later focus changes - e.g. the GPS fix that lands a
 * second after Find My Car opens, which is what turns "your car" into "you
 * and your car."
 */
function FocusController({ bounds }: { bounds: L.LatLngBoundsLiteral }) {
  const map = useMap();
  const boundsKey = JSON.stringify(bounds);
  React.useEffect(() => {
    map.fitBounds(bounds, { maxZoom: 17 });
    // bounds is a fresh array every render; the serialised value is the real dependency.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, boundsKey]);
  return null;
}

/**
 * Web-only real map, using Leaflet + OpenStreetMap tiles (free, no API key -
 * unlike Google Maps' JS API). Not the same underlying library as
 * ParkingMap.native.tsx's react-native-maps, so tile styling differs, but the
 * props and the interaction model are the same: pan/zoom, a pin per marker,
 * tap for details.
 */
export default function ParkingMap(props: ParkingMapProps) {
  const bounds = boundsFor(props);
  return (
    <MapContainer bounds={bounds} style={{ height: '100%', width: '100%' }}>
      <FocusController bounds={bounds} />
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      {props.user ? (
        <CircleMarker
          center={[props.user.lat, props.user.lng]}
          radius={8}
          pathOptions={{ color: '#ffffff', weight: 2, fillColor: '#2563eb', fillOpacity: 1 }}
        >
          <Popup>You are here</Popup>
        </CircleMarker>
      ) : null}
      {props.markers.map((marker) => (
        <Marker
          key={marker.key}
          position={[marker.lat, marker.lng]}
          icon={marker.variant === 'car' ? CAR_ICON : undefined}
        >
          <Popup>{marker.description}</Popup>
        </Marker>
      ))}
    </MapContainer>
  );
}
