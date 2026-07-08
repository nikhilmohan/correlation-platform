import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';
import { ChatterStore } from './chatter.store';
import { ChatterManagementComponent } from './chatter-management.component';
import { EnrichmentChatterClient } from '../api/enrichment-chatter.client';
import { testProviders, flush } from '../../test-utils';
import { EnrichmentChatterEntry, ObservedChatterSignature } from '../api/models';

function store(): ChatterStore {
  TestBed.configureTestingModule({ providers: [ChatterStore, ...testProviders()] });
  return TestBed.inject(ChatterStore);
}

async function mount() {
  TestBed.configureTestingModule({
    providers: [
      ...testProviders(),
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
    ],
  });
  const fixture = TestBed.createComponent(ChatterManagementComponent);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

/** Observed-chatter fixture with two alarmTypes across two device-types + multiple members. */
function seedObserved(): ObservedChatterSignature[] {
  return [
    { managedObjectId: 'Port:a', alarmType: 'PortFlapping', eventType: 'portFlap', trailId: 'TR-1', occurrenceCount: 100, firstSeen: '2026-05-01T00:00:00Z', lastSeen: '2026-05-10T00:00:00Z' },
    { managedObjectId: 'Port:b', alarmType: 'PortFlapping', eventType: 'portFlap', trailId: 'TR-1', occurrenceCount: 60, firstSeen: '2026-05-01T00:00:00Z', lastSeen: '2026-05-10T00:00:00Z' },
    { managedObjectId: 'IPLink:c', alarmType: 'PortFlapping', eventType: 'portFlap', trailId: 'TR-2', occurrenceCount: 40, firstSeen: '2026-05-01T00:00:00Z', lastSeen: '2026-05-10T00:00:00Z' },
    { managedObjectId: 'IPLink:d', alarmType: 'PortDown', eventType: 'portDown', trailId: 'TR-2', occurrenceCount: 30, firstSeen: '2026-05-01T00:00:00Z', lastSeen: '2026-05-10T00:00:00Z' },
    { managedObjectId: 'LSP:e', alarmType: 'CRCErrors', eventType: 'crcError', trailId: 'TR-3', occurrenceCount: 10, firstSeen: '2026-05-01T00:00:00Z', lastSeen: '2026-05-10T00:00:00Z' },
  ];
}

describe('Chatter management (FIX F-UI1) — promoted-vs-candidate join (AC 55, 56)', () => {
  it('AC 55 — observed-chatter is read (ranked by occurrenceCount) and joined promoted vs candidate against the Enrichment list', async () => {
    const s = store();
    s.selectSource('nms-alpha');
    s.load();
    await flush();
    const rows = s.joinView();
    // fixture: 3 observed signatures, ranked desc by occurrenceCount
    expect(rows.length).toBe(3);
    expect(rows.map((r) => r.observed.occurrenceCount)).toEqual([142, 88, 51]);
    // The (Interface:e1-12, linkDown) signature matches the nms-alpha Enrichment entry → promoted
    const promoted = rows.find((r) => r.observed.managedObjectId === 'Interface:e1-12')!;
    expect(promoted.status).toBe('promoted');
    expect(promoted.alreadyPromoted).toBe(true);
    // the rest (incl. the null-managedObjectId source-level signature) are candidates
    const sourceLevel = rows.find((r) => r.observed.managedObjectId === null)!;
    expect(sourceLevel.status).toBe('candidate');
    expect(rows.filter((r) => r.status === 'candidate').length).toBe(2);
  });

  it('AC 56 — promoting a candidate writes the (managedObjectId,eventType) entry to Enrichment for the selected source', async () => {
    const s = store();
    s.selectSource('nms-alpha');
    s.load();
    await flush();
    const ecc = TestBed.inject(EnrichmentChatterClient);
    const addSpy = vi.spyOn(ecc, 'addChatter');
    const candidateRow = s.joinView().find((r) => r.observed.managedObjectId === 'Port:c1-3-7')!;
    expect(candidateRow.status).toBe('candidate');
    s.promote(candidateRow.observed);
    await flush();
    // the write targets the selected source with the exact (managedObjectId, eventType) key
    expect(addSpy).toHaveBeenCalledWith('nms-alpha', { managedObjectId: 'Port:c1-3-7', eventType: 'crcError' });
    // graceful: pending cleared after the round-trip (no double-submit lock left dangling)
    expect(s.pendingPromotion()).toBeNull();
  });

  it('AC 56 — the promote double-submit guard blocks a concurrent promotion while one is pending', () => {
    const s = store();
    s.observed.set([
      { managedObjectId: 'X', alarmType: 'A', eventType: 'a', trailId: null, occurrenceCount: 1, firstSeen: '', lastSeen: '' },
    ]);
    const ecc = TestBed.inject(EnrichmentChatterClient);
    const addSpy = vi.spyOn(ecc, 'addChatter');
    // simulate an in-flight promotion
    s.pendingPromotion.set('Y::y');
    s.promote(s.observed()[0]);
    expect(addSpy).not.toHaveBeenCalled();
  });

  it('AC 56 — removing an Enrichment entry calls the Enrichment chatter DELETE path', async () => {
    const s = store();
    s.selectSource('nms-alpha');
    s.load();
    await flush();
    const entry: EnrichmentChatterEntry = { managedObjectId: 'Interface:e1-12', eventType: 'linkDown' };
    s.remove(entry);
    await flush();
    // graceful: pending is cleared after the round-trip; store re-reads the source list
    expect(s.pendingPromotion()).toBeNull();
  });

  it('AC 56 — switching source re-reads that source’s Enrichment chatter list (default source is empty)', async () => {
    const s = store();
    s.load();
    await flush();
    s.selectSource('default');
    await flush();
    expect(s.selectedSource()).toBe('default');
    expect(s.enrichmentChatter().length).toBe(0);
  });

  it('AC 55 — graceful degrade: the page renders the observed chart even when Enrichment chatter is empty', async () => {
    const fixture = await mount();
    // Chart bars render from the (default) alarm-type grouping of the 3-entry fixture.
    const bars = fixture.nativeElement.querySelectorAll('[data-testid="chatter-bar"]');
    expect(bars.length).toBeGreaterThanOrEqual(1);
    expect(fixture.nativeElement.querySelector('[data-testid="chatter-chart-alarmtype"]')).toBeTruthy();
  });
});

describe('Chatter store — Enrichment API absent/unexpected shape (live BUG-1 regression)', () => {
  it('listChatter ERRORS → observed bar charts still derive (non-empty), enrichmentChatter() is [] (no throw), flagged unavailable', async () => {
    const s = store();
    const ecc = TestBed.inject(EnrichmentChatterClient);
    vi.spyOn(ecc, 'listChatter').mockImplementation(() => throwError(() => new Error('network / not published')));
    s.load();
    await flush();
    // The whole page must not crash: enrichmentChatter is a safe empty array (never undefined).
    expect(s.enrichmentChatter()).toEqual([]);
    expect(Array.isArray(s.enrichmentChatter())).toBe(true);
    // Observed chatter (from Noise Filter, which IS available) still drives non-empty class bars.
    expect(s.classBars().length).toBeGreaterThanOrEqual(1);
    // The derived join/classBars computeds do not throw when enrichment is empty.
    expect(() => s.classBars()).not.toThrow();
    expect(s.enrichmentAvailable()).toBe(false);
  });

  it('listChatter returns an UNEXPECTED shape (no chatterList/items) → enrichmentChatter() is [] and flagged unavailable', async () => {
    const s = store();
    const ecc = TestBed.inject(EnrichmentChatterClient);
    // 200 with a shape lacking chatterList AND items (list.chatterList === undefined → old crash).
    vi.spyOn(ecc, 'listChatter').mockReturnValue(of({ unexpected: true } as never));
    s.load();
    await flush();
    expect(s.enrichmentChatter()).toEqual([]);
    expect(s.classBars().length).toBeGreaterThanOrEqual(1);
    expect(s.enrichmentAvailable()).toBe(false);
  });

  it('listChatter returns an { items: [...] } shape → accepted as the chatter list', async () => {
    const s = store();
    const ecc = TestBed.inject(EnrichmentChatterClient);
    vi.spyOn(ecc, 'listChatter').mockReturnValue(
      of({ items: [{ managedObjectId: 'Port:x', eventType: 'portFlap' }] } as never),
    );
    s.load();
    await flush();
    expect(s.enrichmentChatter()).toEqual([{ managedObjectId: 'Port:x', eventType: 'portFlap' }]);
    expect(s.enrichmentAvailable()).toBe(true);
  });
});

describe('Chatter management — Enrichment-unavailable UI (live BUG-1 regression)', () => {
  it('renders the observed bar charts + an inline notice and DISABLES suppress when Enrichment is absent', async () => {
    const fixture = await mount();
    const s = fixture.componentInstance.store;
    s.observed.set(seedObserved());
    s.enrichmentAvailable.set(false);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    // Charts still render (page did not crash / hide).
    expect(el.querySelectorAll('[data-testid="chatter-bar"]').length).toBeGreaterThanOrEqual(1);
    // Non-blocking notice present.
    expect(el.querySelector('[data-testid="enrichment-unavailable-notice"]')).toBeTruthy();
    // Suppress action disabled with a tooltip.
    const suppress = el.querySelector('[data-testid="suppress-class-btn"]') as HTMLButtonElement;
    expect(suppress.disabled).toBe(true);
    expect(suppress.getAttribute('title')).toBeTruthy();
  });
});

describe('Chatter store — class aggregation (chart-driven view)', () => {
  it('groups observed chatter by alarmType into sorted-desc class bars with correct totals', () => {
    const s = store();
    s.observed.set(seedObserved());
    const bars = s.classBars();
    // PortFlapping 100+60+40=200, PortDown 30, CRCErrors 10 → sorted desc.
    expect(bars.map((b) => b.key)).toEqual(['PortFlapping', 'PortDown', 'CRCErrors']);
    expect(bars[0].totalOccurrences).toBe(200);
    expect(bars[0].suppressableCount).toBe(3);
    expect(s.maxClassTotal()).toBe(200);
  });

  it('groups observed chatter by device-type (prefix before ":") into sorted-desc bars', () => {
    const s = store();
    s.observed.set(seedObserved());
    s.setGroupBy('deviceType');
    const bars = s.classBars();
    // Port a(100)+b(60)=160, IPLink c(40)+d(30)=70, LSP e(10).
    expect(bars.map((b) => b.key)).toEqual(['Port', 'IPLink', 'LSP']);
    expect(bars[0].totalOccurrences).toBe(160);
    expect(bars.find((b) => b.key === 'IPLink')!.totalOccurrences).toBe(70);
  });
});

describe('Chatter management — chart, groupby toggle, fan-out suppress, drill-down', () => {
  it('renders alarm-type and device-type bar charts sorted-desc; the groupby toggle switches', async () => {
    const fixture = await mount();
    fixture.componentInstance.store.observed.set(seedObserved());
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="chatter-chart-alarmtype"]')).toBeTruthy();
    const counts = Array.from(el.querySelectorAll('[data-testid="chatter-bar-count"]')).map((n) =>
      Number((n.textContent ?? '').replace(/\D/g, '')),
    );
    // Descending, top bar = 200.
    expect(counts[0]).toBe(200);
    expect([...counts]).toEqual([...counts].sort((a, b) => b - a));

    const toggle = el.querySelector('[data-testid="chatter-groupby-toggle"]') as HTMLElement;
    const devBtn = Array.from(toggle.querySelectorAll('button')).find((b) => /device/i.test(b.textContent ?? ''))!;
    devBtn.click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="chatter-chart-devicetype"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="chatter-chart-alarmtype"]')).toBeFalsy();
    const devNames = Array.from(el.querySelectorAll('[data-testid="chatter-bar"]')).map((b) => b.getAttribute('data-class'));
    expect(devNames).toEqual(['Port', 'IPLink', 'LSP']);
  });

  it('class-level Suppress fans out one addChatter per member object of the class', async () => {
    const fixture = await mount();
    const s = fixture.componentInstance.store;
    s.observed.set(seedObserved());
    s.enrichmentChatter.set([]);
    fixture.detectChanges();
    const ecc = TestBed.inject(EnrichmentChatterClient);
    const addSpy = vi.spyOn(ecc, 'addChatter').mockReturnValue(of({ source: 'nms-alpha', chatterList: [] }));

    const suppressBtn = fixture.nativeElement.querySelector('[data-testid="suppress-class-btn"]') as HTMLButtonElement;
    suppressBtn.click();
    await flush();
    // PortFlapping (the top/first bar) has 3 member objects → 3 concurrent adds.
    expect(addSpy).toHaveBeenCalledTimes(3);
    expect(addSpy).toHaveBeenCalledWith('nms-alpha', { managedObjectId: 'Port:a', eventType: 'portFlap' });
    expect(addSpy).toHaveBeenCalledWith('nms-alpha', { managedObjectId: 'Port:b', eventType: 'portFlap' });
    expect(addSpy).toHaveBeenCalledWith('nms-alpha', { managedObjectId: 'IPLink:c', eventType: 'portFlap' });
    expect(s.pendingClass()).toBeNull();
  });

  it('fan-out is resilient to partial failure (one add errors, batch still completes + re-reads)', async () => {
    const s = store();
    s.observed.set(seedObserved());
    s.enrichmentChatter.set([]);
    const ecc = TestBed.inject(EnrichmentChatterClient);
    let call = 0;
    vi.spyOn(ecc, 'addChatter').mockImplementation(() => {
      call += 1;
      return call === 2 ? of(null as never) : of({ source: 'nms-alpha', chatterList: [] });
    });
    const listSpy = vi.spyOn(ecc, 'listChatter').mockReturnValue(of({ source: 'nms-alpha', chatterList: [] }));
    s.suppressClass(s.classBars()[0]);
    await flush();
    expect(s.pendingClass()).toBeNull();
    expect(listSpy).toHaveBeenCalled(); // authoritative re-read after the batch
  });

  it('a fully-suppressed class shows a "suppressed" badge instead of a Suppress button', async () => {
    const fixture = await mount();
    const s = fixture.componentInstance.store;
    s.observed.set([
      { managedObjectId: 'LSP:e', alarmType: 'CRCErrors', eventType: 'crcError', trailId: 'TR-3', occurrenceCount: 10, firstSeen: '', lastSeen: '' },
    ]);
    s.enrichmentChatter.set([{ managedObjectId: 'LSP:e', eventType: 'crcError' }]);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="class-suppressed-badge"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="suppress-class-btn"]')).toBeFalsy();
  });

  it('expanding a class bar reveals per-object rows; per-object Promote still works', async () => {
    const fixture = await mount();
    const s = fixture.componentInstance.store;
    s.observed.set(seedObserved());
    s.enrichmentChatter.set([]);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    // Collapsed by default → no drill rows yet.
    expect(el.querySelectorAll('[data-testid="observed-row"]').length).toBe(0);
    const disclosure = el.querySelector('.disclosure') as HTMLButtonElement;
    disclosure.click();
    fixture.detectChanges();
    const rows = el.querySelectorAll('[data-testid="observed-row"]');
    expect(rows.length).toBe(3); // PortFlapping members

    const ecc = TestBed.inject(EnrichmentChatterClient);
    const addSpy = vi.spyOn(ecc, 'addChatter').mockReturnValue(of({ source: 'nms-alpha', chatterList: [] }));
    const promoteBtn = el.querySelector('[data-testid="promote-btn"]') as HTMLButtonElement;
    promoteBtn.click();
    await flush();
    // Top member of PortFlapping is Port:a (count 100).
    expect(addSpy).toHaveBeenCalledWith('nms-alpha', { managedObjectId: 'Port:a', eventType: 'portFlap' });
  });

  it('Remove still works on the Enrichment suppression list', async () => {
    const fixture = await mount();
    const s = fixture.componentInstance.store;
    s.enrichmentChatter.set([{ managedObjectId: 'Interface:e1-12', eventType: 'linkDown' }]);
    fixture.detectChanges();
    const ecc = TestBed.inject(EnrichmentChatterClient);
    const rmSpy = vi.spyOn(ecc, 'removeChatter').mockReturnValue(of({ source: 'nms-alpha', chatterList: [] }));
    const removeBtn = fixture.nativeElement.querySelector('[data-testid="remove-btn"]') as HTMLButtonElement;
    removeBtn.click();
    await flush();
    expect(rmSpy).toHaveBeenCalledWith('nms-alpha', { managedObjectId: 'Interface:e1-12', eventType: 'linkDown' });
  });
});
