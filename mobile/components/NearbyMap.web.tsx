import React from 'react';
import 'leaflet/dist/leaflet.css';
import L from 'leaflet';
import { MapContainer, TileLayer, Marker, Popup, CircleMarker } from 'react-leaflet';

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

/**
 * Web-only real map, using Leaflet + OpenStreetMap tiles (free, no API key
 * - unlike Google Maps' JS API). Not the same underlying library as
 * NearbyMap.native.tsx's react-native-maps (which has no web renderer at
 * all), so tile styling looks different, but the interaction model is the
 * same: pan/zoom, one marker per sign, tap for details.
 */
export default function NearbyMap({ userLat, userLng, groups }: Props) {
  return (
    <MapContainer
      center={[userLat, userLng]}
      zoom={16}
      style={{ height: '100%', width: '100%' }}
    >
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <CircleMarker center={[userLat, userLng]} radius={8} pathOptions={{ color: '#2563eb', fillColor: '#2563eb', fillOpacity: 1 }}>
        <Popup>You are here</Popup>
      </CircleMarker>
      {groups.map((group) => (
        <Marker key={group.key} position={[group.lat, group.lng]}>
          <Popup>{group.description}</Popup>
        </Marker>
      ))}
    </MapContainer>
  );
}
