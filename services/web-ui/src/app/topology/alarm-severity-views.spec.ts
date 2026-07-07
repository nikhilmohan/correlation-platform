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
    // A stubbed client whose /alarms response changes between the two pulls.
    let call = 0;
    const first = page([]); // no active fault → LON green
    const second = page([alarm({ managedObjectId: 'Router:lon-r1', perceivedSeverity: 'critical', lifecycleState: 'open' })]);
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
        { provide: AlarmManagerClient, useValue: { listAlarms: () => of(call++ === 0 ? first : second) } },
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

    // Click Refresh → second pull brings the critical → LON turns red (fault).
    const btn = fixture.nativeElement.querySelector('[data-testid="map-refresh"]') as HTMLButtonElement;
    btn.click();
    await flush();
    fixture.detectChanges();
    expect(call).toBeGreaterThanOrEqual(2); // the client was called again
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
    let call = 0;
    const first = page([]);
    const second = page([alarm({ managedObjectId: 'Router:lon-r1', perceivedSeverity: 'critical', lifecycleState: 'open' })]);
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        { provide: AlarmManagerClient, useValue: { listAlarms: () => of(call++ === 0 ? first : second) } },
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

    const btn = fixture.nativeElement.querySelector('[data-testid="site-graph-refresh"]') as HTMLButtonElement;
    expect(btn.getAttribute('aria-label')).toBeTruthy();
    btn.click();
    await flush();
    fixture.detectChanges();
    expect(call).toBeGreaterThanOrEqual(2);
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
