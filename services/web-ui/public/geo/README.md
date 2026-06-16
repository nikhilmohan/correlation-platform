# Offline basemap asset — `europe.json`

`europe.json` is the country-outline basemap used by the geo-site map
(`src/app/topology/geo-site-map.component.ts`). It is served as a static asset
(`angular.json` globs `public/**`; nginx `try_files` serves `.json`) and loaded by
MapLibre GL as a local GeoJSON source, so the map renders **fully offline** — no remote
tiles, glyphs or sprite.

## Source & license

- **Source:** Natural Earth, 110m admin-0 countries —
  <https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_admin_0_countries.geojson>
- **License:** **Public Domain** (Natural Earth — <https://www.naturalearthdata.com/about/terms-of-use/>).
  Natural Earth data is in the public domain; no attribution is required (permissive).

## How it is generated

Regenerated with the one-off script `scripts/build-europe-basemap.mjs`, which:

1. downloads the source FeatureCollection (network used only at generation time);
2. clips features to the UK/EU viewport bbox `lon[-12, 32] lat[34, 62]`
   (keeps any country with a vertex in the box, so coastlines/borders stay continuous);
3. rounds every coordinate to 3 decimals (~110 m) to keep the payload ~30–90 KB;
4. strips properties to the country `name`.

```sh
curl -sSL -o /tmp/ne_110m.geojson \
  https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_admin_0_countries.geojson
node scripts/build-europe-basemap.mjs /tmp/ne_110m.geojson public/geo/europe.json
```

Current asset: ~38 KB, 42 country features.
