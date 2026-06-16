import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { TopologyStore } from './topology.store';
import { iconKeyForObjectType } from './type-icon-mapper';
import { testProviders, flush } from '../../test-utils';

function store(): TopologyStore {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [...testProviders()] });
  return TestBed.inject(TopologyStore);
}

/** Root a fresh store at the site and return its sorted device ids (the store clears + reseeds). */
async function nodeIdsAtSite(s: TopologyStore, siteId: string): Promise<string[]> {
  s.selectSite(siteId);
  await flush();
  return s.derivedNodes().map((n) => n.managedObjectId).sort();
}

/**
 * Distinct per-site mock fixtures (no London-clone). FAILS on the old fixtures where every
 * non-LON site returned `{...SITE_OBJECTS, siteId}` — so LON, FRA and MAD all had IDENTICAL node
 * ids ("all sites look identical"). Now each site is its own subgraph (AC 60-62 are real).
 */
describe('Distinct per-site fixtures + all-10-objectType coverage (AC 60-62, 70-71)', () => {
  it('LON, FRA and MAD return DISTINCT node sets (no clones)', async () => {
    const s = store();
    s.loadSites();
    await flush();
    const lon = await nodeIdsAtSite(s, 'Site:LON');
    const fra = await nodeIdsAtSite(s, 'Site:FRA');
    const mad = await nodeIdsAtSite(s, 'Site:MAD');

    // Each site has its own ids — none equals another (the old clone made these identical).
    expect(lon).not.toEqual(fra);
    expect(lon).not.toEqual(mad);
    expect(fra).not.toEqual(mad);

    // And there is no overlap collapse: MAD's distinctive ids never appear at LON.
    expect(lon.some((id) => id.includes('mad'))).toBe(false);
    expect(mad.some((id) => id.includes('lon'))).toBe(false);

    // Distinct counts too (LON=6, FRA=3, MAD=7) — a clone would have made them equal.
    expect(lon.length).toBe(6);
    expect(fra.length).toBe(3);
    expect(mad.length).toBe(7);
  });

  it('the union of LON/FRA/MAD covers ALL TEN Core IP objectType icon keys + the generic fallback', async () => {
    const s = store();
    s.loadSites();
    await flush();
    const keys = new Set<string>();
    for (const siteId of ['Site:LON', 'Site:FRA', 'Site:MAD']) {
      s.selectSite(siteId);
      await flush();
      for (const n of s.derivedNodes()) {
        keys.add(iconKeyForObjectType(n.objectType));
      }
    }
    // All ten distinct type-icon keys are exercised across the sites.
    for (const k of ['router', 'linecard', 'port', 'interface', 'fiber-span', 'ip-link', 'igp-adjacency', 'lsp', 'vpn-service', 'srlg']) {
      expect(keys.has(k)).toBe(true);
    }
    // The unknown objectType (UnknownFutureThing in MAD) resolves to the generic fallback (AC 71).
    expect(keys.has('generic')).toBe(true);
  });

  it('the dedicated all-types site (Site:ALL) renders one of every objectType in a SINGLE graph (AC 70/71)', async () => {
    const s = store();
    s.loadSites();
    await flush();
    s.selectSite('Site:ALL');
    await flush();
    const keys = new Set(s.derivedNodes().map((n) => iconKeyForObjectType(n.objectType)));
    // 10 type keys + generic (the unknown node) = 11 distinct icon keys in one site render.
    expect(keys.size).toBe(11);
    expect(s.derivedNodes().some((n) => iconKeyForObjectType(n.objectType) === 'generic')).toBe(true);
  });
});
