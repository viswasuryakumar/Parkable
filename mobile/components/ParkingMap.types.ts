/**
 * Shared contract + viewport maths for the two ParkingMap implementations
 * (react-native-maps on native, Leaflet on web - no single library renders
 * both). Keeping the geometry here means the two platforms can't drift into
 * showing different amounts of ground for the same props.
 */

export type LatLng = { lat: number; lng: number };

export type MapMarker = {
  key: string;
  lat: number;
  lng: number;
  description: string;
  /** 'car' is where YOU parked, drawn distinctly from the sign pins around it. */
  variant?: 'sign' | 'car';
};

/** Where the map looks, and how much ground it covers, in metres across. */
export type MapFocus = { center: LatLng; spanMeters: number };

export type ParkingMapProps = {
  markers: MapMarker[];
  /** Phone position, or null while a GPS fix is still pending / was denied. */
  user?: LatLng | null;
  /** Explicit viewport; defaults to a neighbourhood-sized box around the user. */
  focus?: MapFocus;
};

const METERS_PER_DEGREE_LAT = 111_320;

/** Roughly the walkable neighbourhood around you - matches what /nearby returns. */
const DEFAULT_SPAN_METERS = 1_000;

export function resolveFocus({ markers, user, focus }: ParkingMapProps): MapFocus {
  if (focus) {
    return focus;
  }
  if (user) {
    return { center: user, spanMeters: DEFAULT_SPAN_METERS };
  }
  const first = markers[0];
  if (first) {
    return { center: { lat: first.lat, lng: first.lng }, spanMeters: DEFAULT_SPAN_METERS };
  }
  // Nothing to look at: show the world rather than a confident pin on Null Island.
  return { center: { lat: 0, lng: 0 }, spanMeters: 20_000_000 };
}

/** Degrees of latitude/longitude that cover `spanMeters` at this latitude. */
export function focusDeltas(focus: MapFocus): { latDelta: number; lngDelta: number } {
  const latDelta = focus.spanMeters / METERS_PER_DEGREE_LAT;
  // Longitude degrees shrink towards the poles; clamped so a near-polar
  // latitude can't blow the east-west span up to infinity.
  const cosLat = Math.max(Math.cos((focus.center.lat * Math.PI) / 180), 0.01);
  return { latDelta, lngDelta: latDelta / cosLat };
}

/**
 * The smallest viewport that comfortably holds every point - used by Find My
 * Car to frame you and your car together, however far apart they are.
 *
 * `minSpanMeters` matters more than it looks: when you're standing next to
 * your car the two points are metres (or GPS noise) apart, and framing that
 * exactly would zoom into a featureless block of tiles with no street context
 * to recognise.
 */
export function fitFocus(points: LatLng[], minSpanMeters = 200): MapFocus {
  if (points.length === 0) {
    return { center: { lat: 0, lng: 0 }, spanMeters: minSpanMeters };
  }
  const lats = points.map((p) => p.lat);
  const lngs = points.map((p) => p.lng);
  const minLat = Math.min(...lats);
  const maxLat = Math.max(...lats);
  const minLng = Math.min(...lngs);
  const maxLng = Math.max(...lngs);
  const center = { lat: (minLat + maxLat) / 2, lng: (minLng + maxLng) / 2 };
  const cosLat = Math.max(Math.cos((center.lat * Math.PI) / 180), 0.01);
  const latSpan = (maxLat - minLat) * METERS_PER_DEGREE_LAT;
  const lngSpan = (maxLng - minLng) * METERS_PER_DEGREE_LAT * cosLat;
  // 1.8x padding: a pin sitting exactly on the frame edge reads as off-screen.
  return { center, spanMeters: Math.max(minSpanMeters, Math.max(latSpan, lngSpan) * 1.8) };
}
