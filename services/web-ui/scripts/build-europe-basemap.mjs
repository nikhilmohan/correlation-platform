/**
 * One-off generator for the committed offline basemap asset `public/geo/europe.json`.
 *
 * Source: Natural Earth 110m admin-0 countries (Public Domain) —
 *   https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_admin_0_countries.geojson
 *
 * Pipeline (network used ONLY at generation time; the committed asset renders fully OFFLINE):
 *   1. Read the source FeatureCollection from a local download.
 *   2. Clip features to the UK/EU viewport bbox lon[-12,32] lat[34,62]: keep any feature whose
 *      geometry has at least one ring vertex inside the bbox (whole-country polygons are kept so
 *      coastlines/borders stay continuous at the viewport edge).
 *   3. Round every coordinate to 3 decimal places (~110 m) to shrink the payload to ~30-90 KB.
 *   4. Strip properties to just the country name (rendering needs no attributes).
 *
 * Usage:  node scripts/build-europe-basemap.mjs <src.geojson> public/geo/europe.json
 */
import { readFileSync, writeFileSync } from 'node:fs';

const [, , srcPath, outPath] = process.argv;
if (!srcPath || !outPath) {
  console.error('usage: node build-europe-basemap.mjs <src.geojson> <out.json>');
  process.exit(1);
}

const BBOX = { minLon: -12, minLat: 34, maxLon: 32, maxLat: 62 };
const DECIMALS = 3;

const round = (n) => Math.round(n * 10 ** DECIMALS) / 10 ** DECIMALS;

/** True if [lon,lat] falls inside the viewport bbox. */
const inBbox = ([lon, lat]) =>
  lon >= BBOX.minLon && lon <= BBOX.maxLon && lat >= BBOX.minLat && lat <= BBOX.maxLat;

/** Walk a nested coordinate array; round leaf [lon,lat] pairs and note if any is inside the bbox. */
function processCoords(coords, state) {
  if (typeof coords[0] === 'number') {
    if (inBbox(coords)) state.hit = true;
    return [round(coords[0]), round(coords[1])];
  }
  return coords.map((c) => processCoords(c, state));
}

const fc = JSON.parse(readFileSync(srcPath, 'utf8'));
const features = [];
for (const f of fc.features) {
  if (!f.geometry) continue;
  const state = { hit: false };
  const coordinates = processCoords(f.geometry.coordinates, state);
  if (!state.hit) continue; // no vertex in viewport → drop the whole feature
  features.push({
    type: 'Feature',
    properties: { name: f.properties?.NAME ?? f.properties?.name ?? '' },
    geometry: { type: f.geometry.type, coordinates },
  });
}

const out = { type: 'FeatureCollection', features };
writeFileSync(outPath, JSON.stringify(out));
console.error(
  `wrote ${outPath}: ${features.length} features, ${(JSON.stringify(out).length / 1024).toFixed(1)} KB`,
);
