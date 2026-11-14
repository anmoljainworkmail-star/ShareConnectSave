// Pure geometry for the map-based radar (v2). No map library, no Angular, no
// DOM -- just numbers in, coordinates out.
//
// Single Responsibility (S) + testability: the component's job is "wire the
// map, the scan and the template together". How far a metre is in degrees of
// longitude, or what polygon makes a radar wedge, is a separate question with a
// separate reason to change -- so it lives here as plain functions that can be
// unit-tested without booting a map or a browser.

// GeoJSON positions are [longitude, latitude] -- the OPPOSITE order from the
// "lat, lng" people say out loud. Mixing them up puts a pin in the ocean, which
// is why every function here names its parameters instead of passing bare pairs.
export type Position = [lng: number, lat: number];

export interface GeoPoint {
  lat: number;
  lng: number;
}

// Metres in one degree of latitude. Constant everywhere on Earth (to within a
// fraction of a percent), which is what makes the flat-earth maths below valid.
const METRES_PER_DEGREE_LAT = 111_320;

// Why a "local flat patch" is good enough: the radar is at most a few
// kilometres across. Over that distance the Earth's curvature changes the
// answer by well under a metre, so treating the ground as a flat grid centred
// on the user is accurate enough and far simpler than great-circle maths.
// Longitude is the one thing that is NOT constant: degrees of longitude get
// narrower toward the poles, so they are scaled by cos(latitude).
export function offsetMeters(origin: GeoPoint, eastMeters: number, northMeters: number): Position {
  const metresPerDegreeLng = METRES_PER_DEGREE_LAT * Math.cos((origin.lat * Math.PI) / 180);
  return [origin.lng + eastMeters / metresPerDegreeLng, origin.lat + northMeters / METRES_PER_DEGREE_LAT];
}

// A closed ring of `steps` points around the origin -- a polygon is the only
// way to draw a "circle" on a map, because the map has no circle primitive.
export function circleRing(origin: GeoPoint, radiusMeters: number, steps = 128): Position[] {
  const ring: Position[] = [];
  for (let step = 0; step <= steps; step++) {
    const angle = (step / steps) * Math.PI * 2;
    ring.push(offsetMeters(origin, Math.cos(angle) * radiusMeters, Math.sin(angle) * radiusMeters));
  }
  return ring;
}

// A pie slice from the origin out to radiusMeters, spanning [angle - span, angle].
// The radar sweep is a stack of these with falling opacity (see the component).
export function wedgeRing(origin: GeoPoint, endAngleRad: number, spanRad: number, radiusMeters: number, steps = 6): Position[] {
  const originPosition: Position = [origin.lng, origin.lat];
  const ring: Position[] = [originPosition];
  for (let step = 0; step <= steps; step++) {
    const angle = endAngleRad - spanRad + (spanRad * step) / steps;
    ring.push(offsetMeters(origin, Math.cos(angle) * radiusMeters, Math.sin(angle) * radiusMeters));
  }
  ring.push(originPosition);
  return ring;
}

// FALLBACK ONLY: discovery-service now sends bearing_deg alongside distance_km,
// and the map uses that. This placeholder applies when it is absent (older
// backend, BLE or degraded cards), where the angle is a stable, id-derived
// value used only to spread people around the
// circle instead of stacking them on one line. The golden angle (about 137.5
// degrees) is the classic way to space N points evenly without knowing N:
// each new id lands in the biggest remaining gap. It must never be presented
// as a real compass direction.
const GOLDEN_ANGLE_DEGREES = 137.508;

export function placeholderBearingRadians(userId: number): number {
  return (((userId * GOLDEN_ANGLE_DEGREES) % 360) * Math.PI) / 180;
}
