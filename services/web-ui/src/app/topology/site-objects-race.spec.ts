import { TestBed, ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { Observable, Subject, of } from 'rxjs';
import { testProviders, flush } from '../../test-utils';
import { GeoSiteMapComponent } from './geo-site-map.component';
import { TopologyStore } from './topology.store';
import { TopologyClient } from '../api/topology.client';
import { AlarmManagerClient } from '../api/alarm-manager.client';
import { AlarmPage, AlarmSummary, SiteListDto, SiteObjectsDto } from '../api/models';

/**
 * REGRESSION — the SITE-level severity race that a LIVE run surfaced (PR #417).
 *
 * `loadSites()` is ASYNC. The old code fired a ONE-SHOT `loadAllSiteObjects()` in ngOnInit, right
 * after `loadSites()` — but at that instant `sites()` is still EMPTY, so `loadAllSiteObjects()`
 * early-returned (its `sites.length === 0` guard) and was NEVER retried once the sites arrived. The
 * per-site object cache (`siteObjectMoids`) therefore stayed empty and EVERY site fell back to green
 * ("monitored"), even though real alarm data means every site should be RED.
 *
 * The existing unit tests missed this because the mock backend resolves `of(...)` SYNCHRONOUSLY, so
 * `sites()` was already populated by the time the one-shot ran. These tests use an ASYNC sites feed
 * (a deferred Subject) to reproduce the real timing, and assert the fix: the per-site objects
 * fan-out (`/topology/sites/{id}/objects`) runs REACTIVELY once sites arrive, the cache populates,
 * and a site with an active critical alarm on one of its devices renders RED — not monitored.
 */

const SITES: SiteListDto = {
  domain: 'core-ip',
  snapshotId: 'snap-1',
  count: 2,
  sites: [
    { siteId: 'Site:MIL-01', name: 'Milan', latitude: 45.46, longitude: 9.19, region: 'IT' },
    { siteId: 'Site:LON-01', name: 'London', latitude: 51.5, longitude: -0.12, region: 'UK' },
  ],
};

/** objects-at-site for a given site — one router device whose id carries the site's node token. */
function objectsFor(siteId: string, router: string): SiteObjectsDto {
  return {
    siteId,
    domain: 'core-ip',
    snapshotId: 'snap-1',
    nodeCount: 1,
    edgeCount: 0,
    nodes: [{ managedObjectId: router, objectType: 'Router', domain: 'core-ip', snapshotId: 'snap-1', attributes: {} }],
    edges: [],
  };
}

function alarm(managedObjectId: string, perceivedSeverity: string): AlarmSummary {
  return {
    alarmId: `alm-${managedObjectId}`,
    managedObjectId,
    eventType: 'X',
    perceivedSeverity,
    lifecycleState: 'open',
    role: 'none',
  };
}
function alarmPage(items: AlarmSummary[]): AlarmPage {
  return { items, total: items.length, limit: 500, offset: 0 };
}

/**
 * A TopologyClient stub whose `listSites()` resolves ASYNC (through a Subject we control) so sites()
 * is EMPTY at init — the exact race. `objectsAtSite()` is a spy so we can assert it IS called per
 * site once sites arrive (the bug: it was never called → zero /objects requests).
 */
class AsyncSitesTopologyClient {
  readonly sites$ = new Subject<SiteListDto>();
  readonly objectsAtSite = vi.fn((siteId: string): Observable<SiteObjectsDto> => {
    const router = siteId === 'Site:MIL-01' ? 'Router:mil-r1' : 'Router:lon-r1';
    return of(objectsFor(siteId, router));
  });
  listSites(): Observable<SiteListDto> {
    return this.sites$.asObservable();
  }
  // The site graph / probes are not exercised here; provide inert stubs so nothing crashes.
  neighbors = vi.fn(() => of({ managedObjectId: '', domain: '', neighbors: [] }));
}

describe('site-objects load race — reactive fan-out after ASYNC sites (regression, PR #417)', () => {
  it('objectsAtSite is called for EVERY site once the async sites arrive, and the cache populates', async () => {
    const client = new AsyncSitesTopologyClient();
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        { provide: TopologyClient, useValue: client },
        { provide: AlarmManagerClient, useValue: { listAlarms: () => of(alarmPage([])) } },
      ],
    });
    const store = TestBed.inject(TopologyStore);

    // ── The race window: loadSites() has fired but NOT resolved. sites() is still empty. ──
    store.loadSites();
    // At this instant a one-shot loadAllSiteObjects() (the old ngOnInit order) no-ops:
    expect(store.sites().length).toBe(0);
    expect(client.objectsAtSite).not.toHaveBeenCalled();

    // ── Sites now arrive asynchronously. The reactive effect MUST drive the objects fan-out. ──
    client.sites$.next(SITES);
    client.sites$.complete();
    TestBed.tick(); // flush the effect scheduled by the sites() signal write
    await flush();

    // Every site's /objects was requested (the bug produced ZERO such calls).
    expect(client.objectsAtSite).toHaveBeenCalledWith('Site:MIL-01');
    expect(client.objectsAtSite).toHaveBeenCalledWith('Site:LON-01');
    expect(client.objectsAtSite).toHaveBeenCalledTimes(2);

    // The per-site object cache is now populated, so a site's severity is attributable to real alarms.
    store.alarms.set([alarm('Router:mil-r1', 'critical')]);
    // MIL-01's cached device carries the active critical → RED, NOT the green/monitored fallback.
    expect(store.siteSeverityBucket('Site:MIL-01')).toBe('red');
    // A site with no active fault stays green.
    expect(store.siteSeverityBucket('Site:LON-01')).toBe('green');
  });

  it('the fan-out runs EXACTLY ONCE per site set (no loop / redundant re-fetch on unrelated changes)', async () => {
    const client = new AsyncSitesTopologyClient();
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        { provide: TopologyClient, useValue: client },
        { provide: AlarmManagerClient, useValue: { listAlarms: () => of(alarmPage([])) } },
      ],
    });
    const store = TestBed.inject(TopologyStore);
    store.loadSites();
    client.sites$.next(SITES);
    TestBed.tick();
    await flush();
    expect(client.objectsAtSite).toHaveBeenCalledTimes(2);

    // An unrelated signal write (alarms) + another tick must NOT re-trigger the fan-out.
    store.alarms.set([alarm('Router:mil-r1', 'minor')]);
    TestBed.tick();
    await flush();
    expect(client.objectsAtSite).toHaveBeenCalledTimes(2);
  });

  it('geo map: with ASYNC sites, the site renders RED (fault) — not monitored — end to end', async () => {
    const client = new AsyncSitesTopologyClient();
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        { provide: TopologyClient, useValue: client },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
        // Alarm snapshot: an active critical on MIL-01's device.
        { provide: AlarmManagerClient, useValue: { listAlarms: () => of(alarmPage([alarm('Router:mil-r1', 'critical')])) } },
      ],
    });
    const fixture: ComponentFixture<GeoSiteMapComponent> = TestBed.createComponent(GeoSiteMapComponent);
    fixture.detectChanges(); // ngOnInit → loadSites() (async, still pending) + refreshAlarms()
    await flush();

    // Sites arrive only now — the reactive fan-out must still populate the objects + colour the pins.
    client.sites$.next(SITES);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    const store = TestBed.inject(TopologyStore);
    const mil = store.sites().find((s) => s.siteId === 'Site:MIL-01')!;
    expect(fixture.componentInstance.siteStatusFor(mil)).toBe('fault');
    // The status bar reflects the real bucket — ≥1 critical/major, NOT "Critical/Major: 0".
    expect(fixture.componentInstance.statusCounts().fault).toBeGreaterThanOrEqual(1);
    // The GeoJSON pushed to the clustering source carries the red statusColor for the MIL feature.
    const fc = fixture.componentInstance.sitesGeoJsonForTest();
    const milFeature = fc.features.find((f) => f.properties?.['siteId'] === 'Site:MIL-01')!;
    expect(milFeature.properties?.['status']).toBe('fault');
    expect(milFeature.properties?.['statusColor']).toBe('#ef4444');
  });
});
