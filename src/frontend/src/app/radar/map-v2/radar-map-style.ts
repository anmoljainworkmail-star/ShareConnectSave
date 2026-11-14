import type { Map as MapLibreMap } from 'maplibre-gl';

// "Style as data": OpenFreeMap's stock Liberty style is a JSON document listing
// every layer (parks, water, roads, buildings) and its paint properties. We do
// not fork that document -- we load it as published and then overwrite just the
// colours we care about. Analogy: buying a plain house and repainting the
// rooms, instead of building the house from scratch.
//
// Why patch at runtime instead of hosting our own style JSON: for a first
// version it keeps us on OpenFreeMap's maintained style (new road types, label
// fixes) for free. The cost is a brief flash of stock colours before the patch
// lands, which the loading overlay hides. If that flash or the dependency on
// Liberty's layer ids ever bites, this list converts mechanically into a hosted
// style JSON.
//
// Open/Closed (O): a new palette (for example a night mode) is a new constant
// of this same shape -- applyPalette() does not change.
type PaintPatch = readonly [layerId: string, property: string, value: unknown];

// Colour stops by building height: low blocks stay warm and pale, towers go
// cool. render_height is metres from OpenStreetMap; many Indian buildings have
// no height tag, so 8 m (two storeys) is the stand-in -- a missing height must
// not make a building vanish.
const heightColour = (stops: ReadonlyArray<number | string>): unknown[] => [
  'interpolate',
  ['linear'],
  ['coalesce', ['get', 'render_height'], 8],
  ...stops
];

// Snapchat-style: warm cream land, saturated green and blue, candy-coloured
// buildings, golden main roads. Violet is reserved for the radar overlay so the
// app's own colour is never confused with map colour.
export const VIVID_PALETTE: ReadonlyArray<PaintPatch> = [
  ['background', 'background-color', '#f7efe0'],
  ['landuse_residential', 'fill-color', '#f6e6cf'],
  ['park', 'fill-color', '#8fe0a0'],
  ['park_outline', 'line-color', '#6fd088'],
  ['landcover_grass', 'fill-color', '#8fe0a0'],
  ['landcover_wood', 'fill-color', '#6cd08a'],
  ['landuse_pitch', 'fill-color', '#a7e8b0'],
  ['landuse_school', 'fill-color', '#ffe9a8'],
  ['landuse_hospital', 'fill-color', '#ffc9dd'],
  ['landuse_cemetery', 'fill-color', '#b9e6b0'],
  ['water', 'fill-color', '#4fbcf5'],
  ['waterway_river', 'line-color', '#4fbcf5'],
  ['waterway_other', 'line-color', '#4fbcf5'],
  ['building', 'fill-color', '#ffd9a8'],
  ['building-3d', 'fill-extrusion-color', heightColour([0, '#ffd3a0', 6, '#ffb8cf', 14, '#d2b8ff', 30, '#9fc4ff', 60, '#7fe0d0', 120, '#ffe27a'])],
  ['building-3d', 'fill-extrusion-opacity', 1],
  ['road_minor', 'line-color', '#ffffff'],
  ['road_minor_casing', 'line-color', '#eadcc4'],
  ['road_service_track', 'line-color', '#ffffff'],
  ['road_secondary_tertiary', 'line-color', '#ffd45e'],
  ['road_secondary_tertiary_casing', 'line-color', '#f5b83c'],
  ['road_trunk_primary', 'line-color', '#ffc247'],
  ['road_trunk_primary_casing', 'line-color', '#f29a2e'],
  ['road_link', 'line-color', '#ffd45e'],
  ['road_motorway', 'line-color', '#ffb03a'],
  ['road_path_pedestrian', 'line-color', '#fff7e6']
];

// Softer light than the default so building sides read as shaded colour rather
// than washing out to white.
const VIVID_LIGHT = { anchor: 'viewport', color: '#ffffff', intensity: 0.38, position: [1.4, 215, 35] } as const;

export function applyPalette(map: MapLibreMap, palette: ReadonlyArray<PaintPatch>): void {
  for (const [layerId, property, value] of palette) {
    // Guard clause: a future OpenFreeMap style update can rename or drop a
    // layer. Skipping it leaves one stock-coloured feature; throwing would
    // lose the whole map.
    if (!map.getLayer(layerId)) {
      continue;
    }
    map.setPaintProperty(layerId, property, value);
  }
}

export function applyVividStyle(map: MapLibreMap): void {
  applyPalette(map, VIVID_PALETTE);
  map.setLight({ ...VIVID_LIGHT, position: [...VIVID_LIGHT.position] });
}
