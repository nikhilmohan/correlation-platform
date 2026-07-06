/**
 * One-off generator for the committed offline basemap EXTRAS that enrich the geo-site map into a
 * detailed, coloured, fully-offline map (companions to `public/geo/europe.json`, the land/border
 * polygons). All outputs are COMMITTED static assets loaded locally — NO remote fetch at runtime
 * (air-gapped requirement).
 *
 *   - public/geo/graticule.json — a lat/long grid (LineStrings every 5deg) over the UK/EU viewport,
 *     for map "depth"/scale cues. Generated purely from the viewport bbox (no external source).
 *   - public/geo/cities.json    — MAJOR EU/UK city Points (name + lon/lat) for the telco PoP
 *     regions the sites sit in. Coordinates are well-known public-domain city centroids (Natural
 *     Earth `ne_110m_populated_places` carries the same values; these are hand-listed to stay
 *     offline and aligned to the topology geo catalogue: London, Frankfurt, Madrid, Paris,
 *     Amsterdam, Brussels, Milan, Berlin, Vienna, Zurich, Dublin, Copenhagen).
 *
 * Sizes are tiny (a few KB each). City labels are rendered as DOM overlays by the component
 * (offline-safe — MapLibre symbol text would need a bundled glyph stack the basemap omits), so no
 * glyphs are shipped.
 *
 * Usage: node scripts/build-geo-extras.mjs
 */
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const geoDir = join(here, '..', 'public', 'geo');

const BBOX = { minLon: -12, minLat: 34, maxLon: 32, maxLat: 62 };
const STEP = 5; // degrees between graticule lines

// ── Graticule (lat/long grid) ─────────────────────────────────────────────────────────────────
const graticule = { type: 'FeatureCollection', features: [] };
for (let lon = Math.ceil(BBOX.minLon / STEP) * STEP; lon <= BBOX.maxLon; lon += STEP) {
  const coords = [];
  for (let lat = BBOX.minLat; lat <= BBOX.maxLat; lat += 1) coords.push([lon, lat]);
  graticule.features.push({
    type: 'Feature',
    properties: { kind: 'meridian', deg: lon },
    geometry: { type: 'LineString', coordinates: coords },
  });
}
for (let lat = Math.ceil(BBOX.minLat / STEP) * STEP; lat <= BBOX.maxLat; lat += STEP) {
  const coords = [];
  for (let lon = BBOX.minLon; lon <= BBOX.maxLon; lon += 1) coords.push([lon, lat]);
  graticule.features.push({
    type: 'Feature',
    properties: { kind: 'parallel', deg: lat },
    geometry: { type: 'LineString', coordinates: coords },
  });
}

// ── Major EU/UK cities (public-domain centroids; aligned to the topology PoP regions) ───────────
const CITIES = [
  ['London', -0.1276, 51.5074],
  ['Paris', 2.3522, 48.8566],
  ['Frankfurt', 8.6821, 50.1109],
  ['Berlin', 13.405, 52.52],
  ['Amsterdam', 4.9041, 52.3676],
  ['Brussels', 4.3517, 50.8503],
  ['Madrid', -3.7038, 40.4168],
  ['Milan', 9.19, 45.4642],
  ['Vienna', 16.3738, 48.2082],
  ['Zurich', 8.5417, 47.3769],
  ['Dublin', -6.2603, 53.3498],
  ['Copenhagen', 12.5683, 55.6761],
];
const cities = {
  type: 'FeatureCollection',
  features: CITIES.map(([name, lon, lat]) => ({
    type: 'Feature',
    properties: { name },
    geometry: { type: 'Point', coordinates: [lon, lat] },
  })),
};

writeFileSync(join(geoDir, 'graticule.json'), JSON.stringify(graticule));
writeFileSync(join(geoDir, 'cities.json'), JSON.stringify(cities));
console.error(
  `wrote graticule.json (${graticule.features.length} lines) + cities.json (${cities.features.length} cities)`,
);
