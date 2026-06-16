/**
 * Seeded geo anchors — the single source of truth the AC 33.x journey tests assert against.
 *
 * These are pinned 1:1 to the simulator's grounded geo catalogue
 * (`services/simulator/src/simulator/domains/coreip/geo_catalogue.py`). The `p1-demo` profile runs
 * with `SITE_COUNT=10`, which takes the FIRST 10 entries of the 12-entry catalogue. The Topology
 * Service ingests these and the web-ui geo-site map renders them, so in `E2E_MODE=real` the markers
 * MUST carry exactly these names/regions.
 *
 * DO NOT invent or edit these values — if the simulator catalogue changes, this file changes to
 * match it (and the change is a coordinated seed/UI contract change, flagged to the human).
 *
 * Marker DOM contract (geo-site-map.component.ts):
 *   - text:        <strong>{{ site.name }}</strong>  → e.g. "London Docklands"
 *   - aria-label:  "Site {name} in {region}. Open device graph."  → e.g. "Site London Docklands in UK-South..."
 * The siteId code (e.g. "LON-01") is the simulator's site identifier; the UI labels by `name`, so
 * tests locate markers by `name` (and assert region via the marker text/aria-label).
 */

export interface SeededSite {
  /** Simulator site identifier (geo_catalogue siteId), e.g. "LON-01". */
  readonly code: string;
  /** Display name rendered as the marker label, e.g. "London Docklands". */
  readonly name: string;
  /** Region tag rendered on the marker, e.g. "UK-South". */
  readonly region: string;
}

/**
 * The 10 sites the `p1-demo` profile (SITE_COUNT=10) seeds — the first 10 catalogue entries, in
 * order. Real-mode AC 33.1 asserts every one of these names is present on the map.
 */
export const P1_DEMO_SEEDED_SITES: readonly SeededSite[] = [
  { code: 'LON-01', name: 'London Docklands', region: 'UK-South' },
  { code: 'MAN-01', name: 'Manchester Central', region: 'UK-North' },
  { code: 'AMS-01', name: 'Amsterdam Zuidoost', region: 'EU-West' },
  { code: 'FRA-01', name: 'Frankfurt am Main', region: 'EU-Central' },
  { code: 'PAR-01', name: 'Paris Aubervilliers', region: 'EU-West' },
  { code: 'MAD-01', name: 'Madrid Alcobendas', region: 'EU-South' },
  { code: 'MIL-01', name: 'Milan Caldera', region: 'EU-South' },
  { code: 'STO-01', name: 'Stockholm Kista', region: 'EU-North' },
  { code: 'DUB-01', name: 'Dublin Citywest', region: 'IE' },
  { code: 'WAW-01', name: 'Warsaw Wola', region: 'EU-East' },
] as const;

/** Indicative seeded site count for the p1-demo profile (SITE_COUNT=10). */
export const P1_DEMO_SITE_COUNT = P1_DEMO_SEEDED_SITES.length;

/**
 * The stable drill-in anchor for AC 33.2 / AC 33.3. LON-01 is always the FIRST entry of the
 * first-10 p1-demo set, so it is guaranteed present to drill into for the site-graph + trail tests.
 */
export const DRILL_ANCHOR: SeededSite = P1_DEMO_SEEDED_SITES[0]; // LON-01 / London Docklands

/**
 * A second named anchor AC 33.1 spot-checks by name so the assertion proves the REAL catalogue is
 * rendered (not just "≥10 generic markers"). FRA-01 sits at index 3 of the first-10 set.
 */
export const SPOT_CHECK_ANCHOR: SeededSite = P1_DEMO_SEEDED_SITES[3]; // FRA-01 / Frankfurt am Main
