// 8-point compass, not 16 — "WNW"/"ESE" read as noise to most people; a
// plain word ("Northwest") is unambiguous without needing a legend.
const COMPASS_POINTS = ['North', 'Northeast', 'East', 'Southeast', 'South', 'Southwest', 'West', 'Northwest'];

/**
 * Compass direction from (fromLat, fromLng) to (toLat, toLng), e.g.
 * "Northeast". GPS accuracy alone is commonly 5-20m, so direction is noise
 * below that distance — returns null rather than a misleadingly precise
 * answer (callers should show something like "You're right here" instead).
 */
export function bearingLabel(
  fromLat: number,
  fromLng: number,
  toLat: number,
  toLng: number,
  distanceMeters: number
): string | null {
  if (distanceMeters < 15) {
    return null;
  }
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLng = toRad(toLng - fromLng);
  const lat1 = toRad(fromLat);
  const lat2 = toRad(toLat);
  const y = Math.sin(dLng) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
  const bearing = ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
  return COMPASS_POINTS[Math.round(bearing / 45) % 8];
}

/** "82m" below 1km, "1.2km" at/above — matches how people actually talk about distance. */
export function formatDistance(meters: number): string {
  if (meters < 1000) {
    return `${Math.round(meters)}m`;
  }
  return `${(meters / 1000).toFixed(1)}km`;
}

/** "82m Northeast of you", or "Right where you're standing" at GPS noise floor. */
export function describeLocation(
  fromLat: number,
  fromLng: number,
  toLat: number,
  toLng: number,
  distanceMeters: number
): string {
  const bearing = bearingLabel(fromLat, fromLng, toLat, toLng, distanceMeters);
  if (bearing === null) {
    return "Right where you're standing";
  }
  return `${formatDistance(distanceMeters)} ${bearing} of you`;
}
