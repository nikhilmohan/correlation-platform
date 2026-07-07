import { TestBed, ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { testProviders, flush } from '../../test-utils';
import { GeoSiteMapComponent } from './geo-site-map.component';
import { SiteGraphComponent } from './site-graph.component';
import { TopologyStore } from './topology.store';
import { AlarmManagerClient } from '../api/alarm-manager.client';
import { AlarmPage, AlarmSummary } from '../api/models';

/**
 * Alarm-SEVERITY colouring + Refresh behaviour for the two topology views.
 *
 * The attribution logic is unit-tested in alarm-severity.spec.ts. Here we assert the WIRING: the
 * store derives site- and node-level buckets from the shared alarm snapshot, the geo map colours its
 * pins/status-bar from the site bucket, the site graph tags its device nodes from the node bucket,
 * and both Refresh buttons re-pull the alarm client and re-colour when the alarm set changed.
 */

function alarm(partial: Partial<AlarmSummary> & { managedObjectId: string }): AlarmSummary {
  return {
    alarmId: partial.alarmId ?? `alm-${Math.random()}`,
    managedObjectId: partial.managedObjectId,
    eventType: partial.eventType ?? 'X',
    perceivedSeverity: partial.perceivedSeverity,
    lifecycleState: partial.lifecycleState ?? 'open',
    role: partial.role ?? 'none',
  };
}
function page(items: AlarmSummary[]): AlarmPage {
  return { items, total: items.length, limit: 500, offset: 0 };
}

async function mountGeo(): Promise<ComponentFixture<GeoSiteMapComponent>> {
  TestBed.configureTestingModule({
    providers: [
      ...testProviders(),
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
    ],
  });
  const fixture = TestBed.createComponent(GeoSiteMapComponent);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

async function mountSiteGraph(siteId = 'Site:LON'): Promise<ComponentFixture<SiteGraphComponent>> {
  TestBed.configureTestingModule({ providers: [...testProviders()] });
  const fixture = TestBed.createComponent(SiteGraphComponent);
  fixture.componentRef.setInput('siteId', siteId);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

describe('geo map — site pins coloured by REAL alarm severity', () => {
  it('a site whose device carries an active critical alarm renders RED (fault) with a red pin colour', async () => {
    const fixture = await mountGeo();
    const store = TestBed.inject(TopologyStore);
    // Seed a deterministic snapshot: an ACTIVE critical on a LON device (Router:lon-r1). LON's
    // objects are cached by loadAllSiteObjects on mount, so the site attributes to this alarm.
    store.alarms.set([alarm({ managedObjectId: 'Router:lon-r1', perceivedSeverity: 'critical', lifecycleState: 'open' })]);
    fixture.detectChanges();

    const lon = store.sites().find((s) => s.siteId === 'Site:LON')!;
    expect(fixture.componentInstance.siteStatusFor(lon)).toBe('fault');
    // The status-bar fault count reflects the real bucket (≥1 red site).
    expect(fixture.componentInstance.statusCounts().fault).toBeGreaterThanOrEqual(1);
    // The GeoJSON pushed into the clustering source carries a red statusColor for the LON feature.
    const fc = fixture.componentInstance.sitesGeoJsonForTest();
    const lonFeature = fc.features.find((f) => f.properties?.['siteId'] === 'Site:LON')!;
    expect(lonFeature.properties?.['status']).toBe('fault');
    expect(lonFeature.properties?.['statusColor']).toBe('#ef4444');
  });

  it('the accessible site list carries the status PHRASE (non-colour-only) + data-status', async () => {
    const fixture = await mountGeo();
    const store = TestBed.inject(TopologyStore);
    store.alarms.set([alarm({ managedObjectId: 'Router:lon-r1', perceivedSeverity: 'critical', lifecycleState: 'open' })]);
    fixture.detectChanges();
    const marker = [...fixture.nativeElement.querySelectorAll('[data-testid="site-marker"]')].find(
      (m: Element) => (m.getAttribute('aria-label') ?? '').includes('London'),
    ) as HTMLElement;
    expect(marker.getAttribute('data-status')).toBe('fault');
    expect(marker.getAttribute('aria-label')).toMatch(/critical or major fault/);
  });

  it('map-refresh re-pulls the alarm client AND re-colours when a new critical appears (green → red)', async () => {
    // A stubbed client whose /alarms response changes between the two pulls. refreshAlarms() now makes
    // one paged call PER active state, so the stub returns whatever `current` holds on every call (and
    // reports `total` so pagination terminates after one page); the test swaps `current` to simulate a
    // new fault appearing, and counts the pulls via `calls`.
    let current = page([]); // no active fault → LON green
    const withCrit = page([alarm({ managedObjectId: 'Router:lon-r1', perceivedSeverity: 'critical', lifecycleState: 'open' })]);
    let calls = 0;
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
        { provide: AlarmManagerClient, useValue: { listAlarms: () => { calls++; return of(current); } } },
      ],
    });
    const fixture = TestBed.createComponent(GeoSiteMapComponent);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    const store = TestBed.inject(TopologyStore);

    const lon = store.sites().find((s) => s.siteId === 'Site:LON')!;
    // First pull (empty) → LON has no active fault → monitored (green).
    expect(fixture.componentInstance.siteStatusFor(lon)).toBe('monitored');
    const callsAfterInit = calls;

    // A new critical appears; click Refresh → the re-pull brings it → LON turns red (fault).
    current = withCrit;
    const btn = fixture.nativeElement.querySelector('[data-testid="map-refresh"]') as HTMLButtonElement;
    btn.click();
    await flush();
    fixture.detectChanges();
    expect(calls).toBeGreaterThan(callsAfterInit); // the client was called again
    expect(fixture.componentInstance.siteStatusFor(lon)).toBe('fault');
  });
});

describe('site graph — device nodes coloured by REAL alarm severity', () => {
  it('a device row carries its severity tag + data-severity (non-colour-only status)', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.alarms.set([alarm({ managedObjectId: 'Router:lon-r1', perceivedSeverity: 'critical', lifecycleState: 'open' })]);
    fixture.detectChanges();
    // Open the list view so the accessible device rows are visible/asserted.
    (fixture.nativeElement.querySelector('[data-testid="list-view-toggle"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const rows = [...fixture.nativeElement.querySelectorAll('[data-testid="graph-node"]')] as HTMLElement[];
    const r1 = rows.find((r) => (r.textContent ?? '').includes('lon-r1'))!;
    expect(r1.getAttribute('data-severity')).toBe('red');
    const tag = r1.querySelector('[data-testid="node-severity-tag"]') as HTMLElement;
    expect(tag.getAttribute('data-severity')).toBe('red');
    expect(tag.textContent?.trim()).toBe('Critical/Major');
    expect(r1.getAttribute('aria-label')).toMatch(/critical or major fault/);
  });

  it('a device with only a minor alarm → amber tag; an unaffected device → OK', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.alarms.set([alarm({ managedObjectId: 'Router:lon-r1', perceivedSeverity: 'minor', lifecycleState: 'open' })]);
    fixture.detectChanges();
    expect(store.nodeSeverityBucket('Router:lon-r1')).toBe('amber');
    expect(store.nodeSeverityBucket('LineCard:lon-r1-lc1')).toBe('green');
    expect(fixture.componentInstance.nodeStatusLabel('Router:lon-r1')).toBe('Minor');
    expect(fixture.componentInstance.nodeStatusLabel('LineCard:lon-r1-lc1')).toBe('OK');
  });

  it('site-graph-refresh re-pulls the alarm client and re-colours (green → red on a new critical)', async () => {
    let current = page([]);
    const withCrit = page([alarm({ managedObjectId: 'Router:lon-r1', perceivedSeverity: 'critical', lifecycleState: 'open' })]);
    let calls = 0;
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        { provide: AlarmManagerClient, useValue: { listAlarms: () => { calls++; return of(current); } } },
      ],
    });
    const fixture = TestBed.createComponent(SiteGraphComponent);
    fixture.componentRef.setInput('siteId', 'Site:LON');
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    const store = TestBed.inject(TopologyStore);

    // First pull (empty) → the node is green (OK).
    expect(store.nodeSeverityBucket('Router:lon-r1')).toBe('green');
    const callsAfterInit = calls;

    current = withCrit;
    const btn = fixture.nativeElement.querySelector('[data-testid="site-graph-refresh"]') as HTMLButtonElement;
    expect(btn.getAttribute('aria-label')).toBeTruthy();
    btn.click();
    await flush();
    fixture.detectChanges();
    expect(calls).toBeGreaterThan(callsAfterInit);
    expect(store.nodeSeverityBucket('Router:lon-r1')).toBe('red');
  });

  it('refreshAlarms() delegates to the store (client called again on click)', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    const spy = vi.spyOn(store, 'refreshAlarms');
    (fixture.nativeElement.querySelector('[data-testid="site-graph-refresh"]') as HTMLButtonElement).click();
    expect(spy).toHaveBeenCalled();
  });
});

/**
 * REGRESSION (the reviewer's Major): the alarm fetch must page through / query ALL active states so a
 * faulted node whose alarm sits BEYOND the first page (or in a state other than the first pulled) is
 * NOT dropped. The old single 500-item `listAlarms({limit:500})` fetch missed these → a faulted node
 * rendered green. These drive the STORE directly (no component) against a fake AlarmManagerClient that
 * honours the server-side `state` + `limit`/`offset` paging contract.
 */
describe('TopologyStore.refreshAlarms — complete active-alarm fetch (no silent truncation)', () => {
  /** A paged, state-aware fake `/alarms`: alarms live in `byState`; each state pages by limit/offset. */
  function fakeAlarmClient(byState: Partial<Record<AlarmSummary['lifecycleState'], AlarmSummary[]>>) {
    const calls: Array<{ state?: string; limit?: number; offset?: number }> = [];
    const client = {
      calls,
      listAlarms(opts: { state?: AlarmSummary['lifecycleState']; limit?: number; offset?: number } = {}) {
        calls.push({ state: opts.state, limit: opts.limit, offset: opts.offset });
        const all = opts.state ? (byState[opts.state] ?? []) : Object.values(byState).flat();
        const offset = opts.offset ?? 0;
        const limit = opts.limit ?? 50;
        const items = all.slice(offset, offset + limit);
        const pg: AlarmPage = { items, total: all.length, limit, offset };
        return of(pg);
      },
    };
    return client;
  }

  function bootStore(client: ReturnType<typeof fakeAlarmClient>): TopologyStore {
    TestBed.configureTestingModule({
      providers: [...testProviders(), { provide: AlarmManagerClient, useValue: client }],
    });
    return TestBed.inject(TopologyStore);
  }

  it('pages through a state whose faulted alarm sits BEYOND the first page (old fetch missed it)', async () => {
    // 500 benign open alarms fill page 1; the ONLY critical (on N30) is row 550 — beyond limit 500.
    const openAlarms: AlarmSummary[] = [];
    for (let i = 0; i < 549; i++) {
      openAlarms.push(alarm({ managedObjectId: `Node:BENIGN${i}`, perceivedSeverity: 'warning', lifecycleState: 'open' }));
    }
    openAlarms.push(alarm({ alarmId: 'crit-late', managedObjectId: 'Node:N30', perceivedSeverity: 'critical', lifecycleState: 'open' }));
    const client = fakeAlarmClient({ open: openAlarms });
    const store = bootStore(client);

    store.refreshAlarms();
    await flush();

    // The whole active set was pulled (page 1 + page 2 for the `open` state) → the late critical is present.
    expect(store.alarms().some((a) => a.alarmId === 'crit-late')).toBe(true);
    // And it correctly colours N30 red (the exact regression: a real fault no longer shows green).
    expect(store.nodeSeverityBucket('Node:N30')).toBe('red');
    expect(store.alarmsTruncated()).toBe(false);
    // It paginated the `open` state (offset 0 then 500), not a single call.
    const openCalls = client.calls.filter((c) => c.state === 'open');
    expect(openCalls.length).toBeGreaterThanOrEqual(2);
    expect(openCalls.some((c) => (c.offset ?? 0) >= 500)).toBe(true);
  });

  it('queries EVERY active state (open, in-progress, correlated) so a fault in ANY of them counts', async () => {
    // The only fault is a critical in the `correlated` state — pulling just `open` would miss it.
    const client = fakeAlarmClient({
      correlated: [alarm({ managedObjectId: 'Node:N42', perceivedSeverity: 'critical', lifecycleState: 'correlated' })],
    });
    const store = bootStore(client);

    store.refreshAlarms();
    await flush();

    expect(store.nodeSeverityBucket('Node:N42')).toBe('red');
    const states = new Set(client.calls.map((c) => c.state));
    expect(states.has('open')).toBe(true);
    expect(states.has('in-progress')).toBe(true);
    expect(states.has('correlated')).toBe(true);
    // NEVER pulls the cleared state (we don't fetch+discard cleared rows).
    expect(states.has('cleared')).toBe(false);
  });

  it('flags alarmsTruncated (does NOT silently truncate) when the safety cap is hit', async () => {
    // 6000 open alarms > the 5000-row safety cap → the pull must STOP and flag truncation.
    const many: AlarmSummary[] = [];
    for (let i = 0; i < 6000; i++) {
      many.push(alarm({ managedObjectId: `Node:BULK${i}`, perceivedSeverity: 'warning', lifecycleState: 'open' }));
    }
    const client = fakeAlarmClient({ open: many });
    const store = bootStore(client);

    store.refreshAlarms();
    await flush();

    expect(store.alarmsTruncated()).toBe(true);
    // Bounded: never pulled more than the safety cap of rows.
    expect(store.alarms().length).toBeLessThanOrEqual(5000);
  });
});
