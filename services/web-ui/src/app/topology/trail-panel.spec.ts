import { TestBed, ComponentFixture } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { testProviders, flush } from '../../test-utils';
import { SiteGraphComponent } from './site-graph.component';
import { TopologyStore } from './topology.store';
import { TrailBuilderClient } from '../api/trail-builder.client';
import { ListTrailsResponse, TrailDetail, TrailsForObjectResponse } from '../api/models';

/**
 * Component-level (DOM) tests for the trail-detail panel and site-boundary legend in the device
 * graph (AC 60, 65, 66, 68). These assert the RENDERED DOM (not just the store signal), driving the
 * real SiteGraphComponent template against the mock backend (and, for AC 65, a Trail Builder stub
 * with both optional fields populated, per the design's named test).
 */

async function mountSiteGraph(): Promise<ComponentFixture<SiteGraphComponent>> {
  TestBed.configureTestingModule({ providers: [...testProviders()] });
  const fixture = TestBed.createComponent(SiteGraphComponent);
  fixture.componentRef.setInput('siteId', 'Site:LON');
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

describe('AC 65 — trail-detail panel renders trailId, igpArea, srlgGroup + members', () => {
  it('shows trailId, igpArea, srlgGroup and each member managedObjectId (both fields populated)', async () => {
    const detail: TrailDetail = {
      trailId: 'TR-BOTH',
      domain: 'core-ip',
      snapshotId: 'current',
      memberCount: 2,
      igpArea: '0.0.0.42',
      srlgGroup: 'SRLG-9',
      members: [
        { managedObjectId: 'Router:lon-r1', objectType: 'Router' },
        { managedObjectId: 'Interface:lon-r1-e1', objectType: 'Interface' },
      ],
    };
    const trailsList: ListTrailsResponse = {
      snapshotId: 'current',
      domain: 'core-ip',
      count: 1,
      trails: [{ trailId: 'TR-BOTH', domain: 'core-ip', memberCount: 2, igpArea: '0.0.0.42', srlgGroup: 'SRLG-9' }],
    };
    const byObject: TrailsForObjectResponse = { managedObjectId: 'x', domain: 'core-ip', trailIds: [] };
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        {
          provide: TrailBuilderClient,
          useValue: {
            listTrails: () => of(trailsList),
            getTrail: () => of(detail),
            getTrailsForObject: () => of(byObject),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(SiteGraphComponent);
    fixture.componentRef.setInput('siteId', 'Site:LON');
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    const store = TestBed.inject(TopologyStore);
    store.selectTrail('TR-BOTH');
    await flush();
    fixture.detectChanges();

    const panel = fixture.nativeElement.querySelector('[data-testid="trail-detail"]') as HTMLElement;
    expect(panel).not.toBeNull();
    const text = panel.textContent ?? '';
    expect(text).toContain('TR-BOTH');
    expect(text).toContain('0.0.0.42'); // igpArea rendered
    expect(text).toContain('SRLG-9'); // srlgGroup rendered

    // Every member's managedObjectId renders as a panel member button.
    const members = panel.querySelectorAll('[data-testid="trail-member"]');
    expect(members.length).toBe(2);
    const memberText = [...members].map((m) => m.textContent ?? '').join(' ');
    expect(memberText).toContain('Router:lon-r1');
    expect(memberText).toContain('Interface:lon-r1-e1');
  });

  it('a null srlgGroup is not rendered as a value (the SRLG segment is absent)', async () => {
    // The default mock TrailDetail (TR-7) has igpArea populated but srlgGroup === null.
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.selectTrail('TR-7');
    await flush();
    fixture.detectChanges();

    const panel = fixture.nativeElement.querySelector('[data-testid="trail-detail"]') as HTMLElement;
    expect(panel).not.toBeNull();
    expect(store.selectedTrailDetail()?.srlgGroup).toBeNull();
    // igpArea (populated) is shown; the SRLG value is NOT shown when null.
    expect(panel.textContent).toContain('0.0.0.0');
    expect(panel.textContent).not.toContain('SRLG');
  });
});

describe('AC 66 — activating a member in the panel selects that node in the graph', () => {
  it('clicking a member button calls selectNode(memberId) → selectedObjectId equals that member', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    const selectSpy = vi.spyOn(store, 'selectNode');

    store.selectTrail('TR-7'); // members include Router:lon-r1 (present in the LON graph)
    await flush();
    fixture.detectChanges();

    const panel = fixture.nativeElement.querySelector('[data-testid="trail-detail"]') as HTMLElement;
    const memberBtn = panel.querySelector('[data-testid="trail-member"]') as HTMLButtonElement;
    expect(memberBtn).not.toBeNull();
    const memberId = store.selectedTrailDetail()!.members[0].managedObjectId;

    memberBtn.click();
    await flush();

    expect(selectSpy).toHaveBeenCalledWith(memberId);
    expect(store.selectedObjectId()).toBe(memberId);
  });
});

describe('AC 68 — device-membership highlight and trail-path highlight coexist', () => {
  it('after device-select then trail-select, both highlightedTrailIds and trailMemberIds are present', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);

    // 1) Select a device that belongs to multiple trails → device-membership highlight (getTrailsForObject).
    store.selectNode('Router:lon-r1');
    await flush();
    expect(store.highlightedTrailIds().size).toBeGreaterThan(0); // TR-7, TR-8 (membership)
    const membership = new Set(store.highlightedTrailIds());

    // 2) Now also select a trail → its full member path lights up (getTrail). Per the impl semantics
    //    selectTrail does NOT clear highlightedTrailIds, so the device-membership highlight SURVIVES
    //    alongside the trail's member-path highlight — both highlight sets are active simultaneously.
    store.selectTrail('TR-7');
    await flush();
    fixture.detectChanges();

    expect(store.trailMemberIds().size).toBeGreaterThan(1); // the trail's full member path
    expect(store.highlightedTrailIds().size).toBeGreaterThan(0); // device-membership highlight still present
    // The membership set is unchanged by the trail selection (it coexists, not replaced).
    expect([...store.highlightedTrailIds()].sort()).toEqual([...membership].sort());

    // The DOM reflects both: trail-cluster rows carry the membership badge AND the selected trail.
    const clusters = fixture.nativeElement.querySelectorAll('[data-testid="trail-cluster"]');
    const highlightedRows = [...clusters].filter((c) => c.classList.contains('highlighted'));
    const selectedRows = [...clusters].filter((c) => c.classList.contains('selected'));
    expect(highlightedRows.length).toBeGreaterThan(0); // membership highlight visible
    expect(selectedRows.length).toBe(1); // explicit trail selection visible
  });
});

describe('AC 60 — single-site graph shows one labelled site-boundary grouping', () => {
  it('a single-site graph yields exactly one site-boundary (legend) item labelled with the site name', async () => {
    TestBed.configureTestingModule({ providers: [...testProviders()] });
    const fixture = TestBed.createComponent(SiteGraphComponent);
    const store = TestBed.inject(TopologyStore);
    // nodeSiteMap matches LOCATED_AT edges against KNOWN siteIds, so the site list must be loaded for
    // the single site box to resolve (the geo-map loads sites in the real nav flow).
    store.loadSites();
    fixture.componentRef.setInput('siteId', 'Site:LON');
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    // The LON objects-at-site fixture attributes every device to Site:LON (via LOCATED_AT) → one box.
    expect(store.distinctSiteIds()).toEqual(['Site:LON']);

    const items = fixture.nativeElement.querySelectorAll('[data-testid="site-legend-item"]');
    expect(items.length).toBe(1);
    // The single site box is labelled with the site's friendly name (the legend is the DOM bridge for
    // the canvas-only Cytoscape compound box).
    expect(items[0].textContent).toContain(store.siteName('Site:LON'));
    // No device is left ungrouped: every derived node maps to the one site.
    for (const n of store.derivedNodes()) {
      expect(store.nodeSiteMap().get(n.managedObjectId)).toBe('Site:LON');
    }
  });
});
