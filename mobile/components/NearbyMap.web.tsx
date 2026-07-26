import React from 'react';

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

/** Web has no react-native-maps renderer - NearbyScreen never shows the map toggle on web, so this should never actually render. */
export default function NearbyMap(_props: Props) {
  return null;
}
